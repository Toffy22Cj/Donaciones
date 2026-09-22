# ADR-036 — Reversión Administrativa de Asignación

## Status
Approved

## Context
ADR-034 en su Parte B reservó la resolución administrativa de `reverseAllocation` para un ADR posterior, una vez que existiera un `ActorRef` humano legítimo. Con la introducción de `HumanActor` mediante NUEVA-5, ahora contamos con el marco para procesar solicitudes administrativas de reversión de asignaciones originadas por interacciones humanas.

## Decision
1. **Conservación de la API de Dominio:** `Fund.reverseAllocation(allocationId, reason)` permanece sin cambios, garantizando que el agregado no se contamina con lógica de autorización de capa de aplicación.

2. **Mantenimiento del Flujo de Compensación (Saga):** El método existente `FundCommandService.reverseAllocation(...)` permanece inalterado. Seguirá dedicado estrictamente al flujo de compensación de `AssetRegisteredSagaPolicy`, y conserva su mecanismo de bypass de seguridad basado en `SystemActor`. `AssetRegisteredSagaPolicy` no se modifica.

3. **Nuevo Punto de Entrada Administrativo:** Se introduce `FundCommandService.reverseAllocationAdministratively(...)` exclusivamente para invocar la operación desde contextos de administración humana.

4. **Nuevo CommandType:** Se crea el tipo de comando `REVERSE_ALLOCATION_ADMINISTRATIVELY`.

5. **Autorización Específica:** El comando `REVERSE_ALLOCATION_ADMINISTRATIVELY` requiere `ADMINISTRATOR` en el `RoleAuthorizationPolicy`. Esta asignación de `ADMINISTRATOR` es una decisión específica para esta operación y NO establece una regla general para futuras operaciones sobre `Fund`.

6. **Flujo de Ejecución:** El flujo administrativo utiliza el mecanismo ya existente y validado:
   `IdentityPrincipalPort` -> `OrganizationBoundaryPolicy` -> `RoleAuthorizationPolicy` -> `Aggregate` (Fund).

7. **Fuera de Alcance:** El transporte y la exposición de este servicio hacia el exterior (HTTP, JMX, autenticación) quedan fuera del alcance de este ADR.

## Consequences
- Preservamos el bypass del sistema de sagas sin acoplamientos impuros.
- Centralizamos la seguridad para operaciones administrativas.
- Evitamos la sobrecarga del modelo de dominio.
