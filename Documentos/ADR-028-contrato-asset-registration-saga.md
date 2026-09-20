# ADR-028 — Contrato y Envelope de Asset Registration Saga

## Estado
Aprobado parcialmente. (Se aprueba la desambiguación semántica. El resto del contrato de envelope se formaliza según el comportamiento empírico actual, a la espera de estandarización futura según ADR-013).

## Contexto
El sistema implementa una arquitectura basada en eventos donde los dominios `Fund` y `PhysicalAsset` son Aggregate Roots transaccionalmente independientes.
Para confirmar la asignación financiera en `Fund` tras la materialización física del donativo, se emplea una saga coordinada mediante el patrón Transactional Outbox.

Existía una inconsistencia en la política consumidora (`AssetRegisteredSagaPolicy`), la cual intentaba leer la identificación de la asignación usando el campo heredado `sourceAllocationId` para activos físicos recién creados (raíz), en lugar de la asignación directa. Esto impedía la confirmación y reversión correcta de los fondos en el caso de creación directa.

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
- **Estabilidad y uso de `messageId`**: Se verificó empíricamente que `OutboxSagaCoordinator` preserva el `messageId` original y lo copia idéntico en cada nuevo intento o puesta en cuarentena. Dado que es estable, la política emplea el `messageId` directamente como `commandId` de intención contra `FundCommandService` para asegurar idempotencia.
- **Uso de `correlationId`**: Se verificó que el sistema actual no utiliza `correlationId` en ningún otro lugar para correlación distribuida ni existe acoplamiento con `allocationId`. Únicamente se usa dentro de `AssetRegisteredSagaPolicy` como un fallback empírico para deducir el `fundId` si este falta en el payload JSON.
- **Convención `-comp`**: Al revertir operaciones (`compensate`), la política anexa el sufijo `"-comp"` al `messageId` para formar el commandId de compensación. Esto se declara estrictamente como una convención *aislada y local* de esta política. La idempotencia real de la reversión financiera ya está amparada en **ADR-009** (que garantiza rechazo por operación subyacente usando el `allocationId`), no dependemos de este string táctico para la seguridad del Aggregate.

## Consecuencias
- Queda resuelto el diseño del payload para confirmación de asignaciones de activos raíz.
- El territorio de la taxonomía de identificadores (ADR-013) y compensación (ADR-009) queda protegido: no se ratifica `-comp` ni el fallback de `correlationId` como patrones arquitectónicos globales, identificándolos correctamente como artefactos locales funcionales y transitorios.
