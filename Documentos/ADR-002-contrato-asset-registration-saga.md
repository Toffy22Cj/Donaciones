# ADR-002 — Contrato y Envelope de Asset Registration Saga

## Estado
Aprobado

## Contexto
El sistema implementa una arquitectura basada en eventos donde los dominios `Fund` y `PhysicalAsset` son Aggregate Roots transaccionalmente independientes.
Para confirmar la asignación financiera en `Fund` tras la materialización física del donativo, se emplea una saga coordinada mediante el patrón Transactional Outbox.

Existía una inconsistencia en la política consumidora (`AssetRegisteredSagaPolicy`), la cual intentaba leer la identificación de la asignación usando el campo heredado `sourceAllocationId` para activos físicos recién creados (raíz), en lugar de la asignación directa. Esto impedía la confirmación y reversión correcta de los fondos en el caso de creación directa.

## Decisiones

Se formaliza el contrato del envelope y payload de la saga `ASSET_REGISTRATION_SAGA` evidenciado en el código, de la siguiente manera:

1. **Uso de `allocationId` para activos raíz:**
   El campo `allocationId` representa la asignación financiera primaria y propia del `PhysicalAsset` raíz. La política `AssetRegisteredSagaPolicy` debe leer estrictamente este campo del payload para invocar `confirmAllocation` o `reverseAllocation` sobre el `Fund` correspondiente.

2. **Rol de `sourceAllocationId`:**
   Este campo representa exclusivamente la asignación financiera heredada por un activo hijo resultante de un evento de división (`ASSET_SPLIT`). Un activo físico raíz **NO** debe utilizar `sourceAllocationId` para confirmar su asignación primaria. (El comportamiento de hijos de split queda fuera de esta saga y requerirá una política propia).

3. **Roles de `parentAssetRef` y `rootAssetRef`:**
   Estos campos rastrean puramente el linaje y genealogía logística/física del activo, y no se utilizan en la resolución de la asignación financiera.

4. **Requisitos mecánicos del `OutboxMessage` (Envelope):**
   Para que `AssetRegisteredSagaPolicy` enrute y ejecute correctamente:
   - **`sagaType`**: Debe ser exactamente `"ASSET_REGISTRATION_SAGA"`.
   - **`messageId`**: Se empleará directamente como `commandId` de la transacción contra `FundCommandService` para asegurar idempotencia.
   - **`payload`**: Debe ser un JSON válido que contenga el campo `allocationId`.
   - **`fundId`**: Debe proveerse en el payload JSON. Si está ausente o es nulo, la política usará el valor de `correlationId` del mensaje como mecanismo de fallback explícito.

## Consecuencias
- Queda establecido el estándar de desambiguación entre la asignación directa (`allocationId`) y la heredada (`sourceAllocationId`), garantizando que la saga raíz procese exclusivamente la asignación de su creación original.
- Para revertir operaciones, el sufijo `-comp` aplicado al `messageId` queda como convención táctica dentro de esta saga (como se evidencia en `reverseAllocation`), sin asumirse aún como un mecanismo estandarizado de arquitectura global.
- El sistema puede ahora implementar un productor de Outbox para el registro de activos raíz apoyándose en este contrato formalizado.
