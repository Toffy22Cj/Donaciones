# ADR-028 — Relación `Organization ↔ Fund`

## Status
Reconstrucción histórica (Aprobada en Fase 5)

## Contexto
El proyecto opera como un monolito modular con separación estricta de Bounded Contexts. El módulo `identity` gestiona identidades, organizaciones y membresías, mientras que `core` gestiona el ciclo de vida y la trazabilidad de fondos (`Fund`) y activos físicos (`PhysicalAsset`).
Durante la Fase 5 (Tarea 5.1), surgió la necesidad de asociar los fondos con las organizaciones que los operan, asegurando que un fondo no pueda ser manipulado por entidades no autorizadas o sin organización asignada.

## Problema
1. ¿Cuál es la naturaleza jurídica/operativa de la relación entre `Organization` y `Fund`?
2. ¿Cómo se representa la organización dentro del agregado `Fund` sin introducir acoplamiento ni dependencias de `core` hacia `identity`?
3. ¿Cómo se gobierna la inmutabilidad y la génesis del fondo respecto a su organización?
4. ¿Cómo deben tratarse los fondos existentes (Funds v1) generados en fases previas que carecen de identificador organizacional?

## Decisión Histórica Reconstruida

### 1. Naturaleza de la relación: Autoridad Operativa
`Organization` **gestiona y opera** el `Fund`. Se establece formalmente como una autoridad operativa, distinguiéndose explícitamente de un concepto de propiedad jurídica o tenencia financiera.

### 2. Desacoplamiento mediante Value Object opaco
El agregado `Fund` incorpora la referencia organizacional mediante un Value Object propio del dominio de fondos:
```java
package com.traceability.core.domain.fund;

public record OrganizationRef(String value) {
    public OrganizationRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("organizationRef cannot be null or blank");
        }
    }
}
```
`core.domain.fund` **no importa nada** de `identity`. `OrganizationRef` encapsula un identificador opaco en forma de cadena.

### 3. Inmutabilidad y Reglas de Génesis
- `Fund.organizationRef` es **obligatorio** en el método de fábrica de génesis (`Fund.create(...)` y `Fund.register(...)`).
- Es **inmutable** durante todo el ciclo de vida del agregado. No existe ningún comando de reasignación organizacional en el alcance de la Fase 5.
- La ausencia de `organizationRef` en un comando de creación dispara `InvalidFundGenesisException` (ADR-028, Regla #4).

### 4. Separación de Guardas (Dominio vs. Aplicación)
Se establecen dos guardas independientes por responsabilidad:
- **Guarda de Dominio (`FundNotAssociatedToOrganizationException`):** Verificada internamente en `Fund.execute()`. Responde a la pregunta: *"¿El fondo es operable en absoluto?"*. Si el fondo carece de `organizationRef`, el aggregate rechaza cualquier comando de mutación.
- **Guarda de Aplicación (`CrossOrganizationAccessException`):** Verificada en la capa de aplicación antes de invocar el agregado. Compara el `organizationId` del actor humano autenticado contra el `organizationRef` del fondo. Cubre tanto el acceso sin organización como el acceso desde una organización distinta.

### 5. Tratamiento de Funds v1 (Legacy)
Para los fondos v1 creados en fases anteriores sin `organizationRef`:
- La reconstitución de eventos (`reconstitute()`), la lectura y la reconstrucción de proyecciones están permitidas.
- La ejecución de nuevos comandos de escritura queda rechazada **permanentemente** mediante `FundNotAssociatedToOrganizationException`.
- Se descartó explícitamente el uso de valores comodín como `UNASSIGNED`, backfill de datos o scripts de migración, por no existir necesidad de negocio demostrada.

## Evidencia en Código y Repositorio
- `com.traceability.core.domain.fund.OrganizationRef.java`: Cita explícita `* Ref: ADR-028`.
- `com.traceability.core.domain.fund.exceptions.InvalidFundGenesisException.java`: Cita explícita `* Ref: ADR-028, Rule #4`.
- `com.traceability.core.domain.fund.exceptions.FundNotAssociatedToOrganizationException.java`: Cita explícita `* Ref: ADR-028`.
- `com.traceability.core.domain.fund.Fund.java`: Implementación de la guarda de inmutabilidad y presencia de `organizationRef`.
- Documentos de referencia: `estado-fase5.md` (§2), `plan-ejecucion-agentes-fase5.md` (Bloque 2, Tarea 5.1).

## Consecuencias
### Positivas
- Preservación estricta de la frontera hexagonal: `core` no depende de `identity`.
- Blindaje de fondos: ninguna operación puede ser ejecutada sobre un fondo sin organización asociada.
- Separación nítida entre la integridad intrínseca del agregado (dominio) y la política de acceso del usuario (aplicación).

### Negativas / Restricciones
- Los fondos heredados de Fase 4 sin `organizationRef` quedan congelados en modo de solo lectura permanente.
