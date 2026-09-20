# ADR-034 — Visibilidad y Operabilidad de Pending Allocation (NUEVA-4 redefinida)

## Estado
Aprobado.

## Contexto
El requisito original (NUEVA-4) planteaba automatizar la creación del activo físico mediante la saga `FundAllocationSagaPolicy`. Sin embargo, la decisión arquitectónica actual ("Opción A") rechaza esa automatización sin evidencia, requiriendo que `PENDING_ALLOCATION` permanezca visible y auditable, con resolución manual.
Actualmente, `Fund` mantiene la información de asignaciones pendientes en su estado (mediante `activeAllocations`, `pendingAllocationAmount` y el evento `ALLOCATION_REQUESTED`). No obstante, no existe una proyección eficiente que exponga las allocations pendientes externamente a un operador humano.
Además, la escritura administrativa (resolución manual vía `reverseAllocation()`) queda bloqueada por la ausencia del Bloque C/D implementado (autorización y cuentas autenticadas).

## Decisión
Se re-define el alcance de NUEVA-4 como "Visibilidad y operabilidad manual de PENDING_ALLOCATION", implementado en dos partes:

**PARTE A (Desbloqueada): Visibilidad**
- Crear un read model / proyección de allocations pendientes.
- Reutilizar el patrón existente de proyecciones: implementando `ProjectionEventHandler` e inyectándose en el `ProjectionEventSource` y `ProjectionRetryScheduler`, manteniendo un checkpoint propio.
- La proyección debe permitir consultar al menos: `allocationId`, `fundId`, monto pendiente, `timestamp`/`ocurredAt` correspondiente a la solicitud, y el estado `REQUESTED`.
- **No** modificar la semántica de `Fund`.
- **No** modificar los contratos de `confirmAllocation` o `reverseAllocation`.
- **No** introducir nuevos eventos de dominio.
- **No** introducir flujos automáticos de expiración (TTL) ni schedulers de expiración.

**PARTE B (Bloqueada): Resolución administrativa**
- La resolución manual administrativa de `reverseAllocation` queda explícitamente fuera de la implementación inicial (Parte A).
- Queda bloqueada hasta que el Bloque C/D tenga un mecanismo real de escritura autenticada y genere un `actorRef` legítimo.
- La Parte B tendrá su propio ADR cuando el Bloque C/D disponga de un mecanismo real de escritura autenticada y `actorRef` legítimo. Este ADR no decide todavía el transporte (HTTP/JMX) ni el mecanismo concreto de exposición administrativa.

## Razón
- La lectura (Parte A) no requiere `actorRef` validado de la misma forma que una mutación de estado.
- La escritura administrativa (Parte B) requiere autenticación obligatoria.
- No se permite usar un `actorRef` provisional ni crear una autenticación inventada (alineado con ADR-031).
- No se incorpora campo `reason`/motivo a `reverseAllocation()` para no crear un nuevo requisito no definido y preservar el contrato intacto que ya consume `AssetRegisteredSagaPolicy.compensate()`.

## Consecuencias
- `PENDING_ALLOCATION` será observable externamente mediante el nuevo read model.
- No cambia el estado de negocio ni la consistencia del agregado financiero.
- Sigue siendo posible que una allocation permanezca pendiente de forma indefinida.
- La resolución manual sigue pendiente de la implementación de Bloque C/D y no se implementa `reverseAllocation` administrativo en esta fase.

## Referencias
- **ADR-001** y **ADR-012**: Separación e independencia entre `Fund` y `PhysicalAsset`.
- **ADR-015** y **ADR-017**: Arquitectura Desacoplada de la Capa de Lectura y Framework de Proyección Genérico (patrón de proyecciones reutilizado).
- **ADR-022 — Resolución Manual de Lotes Atascados (JMX)**: Sienta el precedente de operar manualmente los recursos atascados en lugar de automatizarlos (alineado con Opción A).
- **ADR-031**: Taxonomía de `ActorRef` y aplazamiento de cuentas humanas (sustenta la autorización/actorRef diferida y el bloqueo de la Parte B).
- **ADR-032**: Autorización de comandos en `core`.
