# ADR-033 — Contrato y Envelope de Asset Registration Saga

## Estado
Aprobado parcialmente. (Se aprueba la desambiguación semántica. El resto del contrato de envelope se formaliza según el comportamiento empírico actual, a la espera de estandarización futura según ADR-013).

## Contexto
El sistema implementa una arquitectura basada en eventos donde los dominios `Fund` y `PhysicalAsset` son Aggregate Roots transaccionalmente independientes.
Para confirmar la asignación financiera en `Fund` tras la materialización física del donativo, se emplea una saga coordinada mediante el patrón Transactional Outbox.

Existía una inconsistencia en la política consumidora (`AssetRegisteredSagaPolicy`), la cual intentaba leer la identificación de la asignación usando el campo heredado `sourceAllocationId` para activos físicos recién creados (raíz), en lugar de la asignación directa. Esto impedía la confirmación y reversión correcta de los fondos en el caso de creación directa.

**Estado de validación end-to-end**: Aunque el contrato y sus invariantes han sido verificados contra el código consumidor y contra el comportamiento de `OutboxSagaCoordinator`, el envelope de `ASSET_REGISTRATION_SAGA` todavía no ha sido ejercitado end-to-end por un productor real de `OutboxMessage` en producción. No existe actualmente un productor implementado que emita este `sagaType`. Por tanto, este ADR formaliza un contrato verificado estructuralmente, pero su flujo productor→Outbox→Policy aún no cuenta con validación de integración end-to-end.

## Decisiones

### 1. Resolución Semántica (Aprobada)

Se formaliza el contrato del payload de la saga `ASSET_REGISTRATION_SAGA` evidenciado en el código:

- **Uso de `allocationId` para activos raíz:**
  El campo `allocationId` representa la asignación financiera primaria y propia del `PhysicalAsset` raíz. La política `AssetRegisteredSagaPolicy` debe leer estrictamente este campo del payload para invocar `confirmAllocation` o `reverseAllocation` sobre el `Fund` correspondiente.
- **Rol de `sourceAllocationId`:**
  Este campo representa exclusivamente la asignación financiera heredada por un activo hijo resultante de un evento de división (`ASSET_SPLIT`). Un activo físico raíz **NO** debe utilizar `sourceAllocationId` para confirmar su asignación primaria. (El comportamiento de hijos de split queda fuera de esta saga y requerirá una política propia).
- **Roles de `parentAssetRef` y `rootAssetRef`:**
  Estos campos rastrean puramente el linaje y genealogía logística/física del activo, y no se utilizan en la resolución de la asignación financiera.

### 2. Contrato de Envelope (Comportamiento Factual Registrado)

Las siguientes decisiones representan el estado *táctico* y comprobado del sistema actual. No se declaran como estándares globales definitivos hasta reconciliarlos formalmente con **ADR-009** y **ADR-013**:

- **`sagaType`**: Debe ser exactamente `"ASSET_REGISTRATION_SAGA"`.
- **Estabilidad y uso de `messageId`**: Se verificó empíricamente que `OutboxSagaCoordinator` preserva el `messageId` original y lo copia idéntico en cada nuevo intento de reconstrucción del `OutboxMessage` durante un retry o puesta en cuarentena (el coordinador no genera un nuevo `messageId` en ese proceso). Dado que es estable frente a retries, la política emplea el `messageId` directamente como `commandId` de intención contra `FundCommandService` para asegurar idempotencia (`commandId := messageId`).
- **Uso de `correlationId`**: El uso vigente documentado es estrictamente como fallback empírico de `fundId` dentro de `AssetRegisteredSagaPolicy` si este falta en el payload JSON. No existe actualmente un uso productivo que lo trate como `allocationId` ni como un identificador de correlación distribuida. La propuesta histórica de igualar `correlationId=allocationId` (asociada a una idea anterior de `FundAllocationSagaPolicy`) no fue implementada y no forma parte de esta decisión vigente.
- **Convención `-comp`**: Al revertir operaciones (`compensate`), la política anexa el sufijo `"-comp"` al `messageId` para formar el commandId de compensación. Esto se mantiene explícitamente como una convención táctica *local* de `AssetRegisteredSagaPolicy`, NO como un estándar global. La distinción funcional opera en dos capas diferentes:
  - **ADR-009**: Protege los invariantes de negocio del agregado `Fund`, rechazando una segunda compensación de la misma operación (basado en el identificador único de la operación, ej. `allocationId`).
  - **`messageId + "-comp"`**: Provee protección de infraestructura y deduplicación mediante `commandId` (ej. a través de `PROCESSED_COMMAND_DOCUMENT`). No debe afirmarse que `-comp` sea el mecanismo exclusivo que "cumple" ADR-009, sino una capa complementaria.

## Consecuencias
- Queda resuelto el diseño del payload para confirmación de asignaciones de activos raíz.
- El territorio de la taxonomía de identificadores definido en **ADR-013** queda protegido: la regla `commandId := messageId` es una derivación específica de esta saga; `messageId` sigue siendo un identificador distinto y no se convierte arbitrariamente en `allocationId`, `externalEventId` ni en otro identificador definido por ADR-013. No se ratifica `-comp` ni el fallback de `correlationId` como patrones arquitectónicos globales.
