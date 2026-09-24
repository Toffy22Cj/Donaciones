# ADR-032 — Autorización de Comandos en `core`: Puerto `Identity ↔ Core`, Guardas de Pertenencia y Rol, Matriz de Autorización

## Status
Reconstrucción histórica (Aprobada en Fase 5)

## Contexto
En Fase 5 (Bloque C/D), tras cerrar el modelo de dominio de `Fund` y `PhysicalAsset`, se requería establecer el mecanismo formal para autorizar comandos ejecutados por actores humanos, garantizando que un usuario solo pueda operar sobre recursos de su propia organización y con los roles adecuados, sin romper el desacoplamiento entre los módulos `core` e `identity`.

## Problema
1. ¿Cómo comunica `identity` los datos del principal de autorización a `core` sin generar dependencias cíclicas ni directas entre ambos módulos?
2. ¿Cómo se valida la pertenencia organizacional del usuario frente al recurso solicitado?
3. ¿Cuál es la composición y el orden de evaluación de las guardas de seguridad en la capa de aplicación?
4. ¿Qué roles de negocio están autorizados para ejecutar cada comando del `core` (Matriz de Autorización)?
5. ¿Cómo se comportan los actores de sistema (`SystemActor`) y externos (`ExternalActor`) frente a estas políticas?

## Decisión Histórica Reconstruida

### 1. D1 — Puerto `Identity ↔ Core` en `contracts`
Se establece el módulo neutral `contracts` como puente de comunicación:
- `IdentityPrincipalPort` (interfaz en `contracts`): Permite resolver la identidad de un actor mediante `resolvePrincipal(String accountId)`.
- `AuthorizationPrincipal` (DTO inmutable en `contracts`): Contiene `accountId: String`, `organizationId: String` (nullable) y `roles: Set<AuthorizationRole>`.
- `AuthorizationRole`: Enum propio del contrato (`contracts.authorization.AuthorizationRole`), actuando como Anti-Corruption Layer (ACL).
- Las dependencias son unidireccionales hacia `contracts`: `identity → contracts` y `core → contracts`. **No existe dependencia directa `core ↔ identity` en ningún sentido.**

### 2. D2 — Guarda de Pertenencia Organizacional (`OrganizationBoundaryPolicy`)
Componente stateless en `core.application.authorization`:
- Firma: `assertBelongs(String principalOrganizationId, String resourceOrganizationRef)`.
- Si `principalOrganizationId` es nulo, o si existe discrepancia (*mismatch*) con el `organizationRef` del agregado, lanza inmediatamente `CrossOrganizationAccessException`.
- Se evalúa **estrictamente antes** que la política de roles. Ningún rol (incluso administrador) puede operar sobre recursos de otra organización.

### 3. D3 — Composición Secuencial en Application Services
El flujo obligatorio de ejecución en cada `CommandService` es:
1. `OrganizationBoundaryPolicy.assertBelongs(...)` → Falla con `CrossOrganizationAccessException`.
2. `RoleAuthorizationPolicy.authorize(principal, commandType)` → Falla con `InsufficientRoleException`.
3. Invocación de la lógica de negocio en el agregado (`Aggregate.execute(command)`).

### 4. D4 — Mecanismo Centralizado de Roles (`RoleAuthorizationPolicy`)
- Se define el enum cerrado `CommandType` en `core.application.authorization`.
- `RoleAuthorizationPolicy.authorize(...)` utiliza una expresión `switch` de Java 21 exhaustiva sin cláusula `default`, garantizando en tiempo de compilación que ningún comando nuevo carezca de política de roles.
- Se implementó una regla en `ArchitectureTest` (ArchUnit) que exige que todos los servicios de comando inyecten `RoleAuthorizationPolicy`.

### 5. D5 — Matriz de Autorización de Negocio (Ejecución Humana)
Las seis celdas iniciales aprobadas para actores humanos son:
| CommandType | Roles Permitidos | Justificación de Negocio |
|---|---|---|
| `REGISTER_FUND` | `{ADMINISTRATOR}` | Creación de compromiso financiero preliminar. |
| `CLEAR_FUNDS_AS_GENESIS` | `{ADMINISTRATOR}` | Entrada líquida de fondos directos sin promesa previa. |
| `CLEAR_FUNDS_FOR_PLEDGE` | `{ADMINISTRATOR}` | Materialización de fondos prometidos en líquido. |
| `REGISTER_PHYSICAL_ASSET` | `{EMPLOYEE}` | Operación logística/operativa de alta de activo físico. |
| `REGISTER_PHYSICAL_ASSET_FROM_DONATION` | `{EMPLOYEE}` | Alta operativa directa de activo físico (Donación en Especie). |
| `SPLIT_PHYSICAL_ASSET` | `{EMPLOYEE}` | Operación logística de fraccionamiento de lote existente. |

*Nota:* `REPRESENTATIVE` fue deliberadamente excluido de los comandos operativos directos iniciales; no existen reglas de jerarquía implícita (e.g. `ADMINISTRATOR` no hereda automáticamente los permisos de `EMPLOYEE`).

### 6. D6 — Bypass para `SystemActor` y `ExternalActor`
Las guardas `OrganizationBoundaryPolicy` y `RoleAuthorizationPolicy` aplican exclusivamente a actores humanos (`HumanActor` / `HumanAccount`).
- `SystemActor` efectúa *bypass* porque transporta causalidad interna y herencia garantizada por la saga.
- `ExternalActor` efectúa *bypass* en las políticas de usuario humano (dejando la derivación de contexto organizacional sujeta a políticas de integración externa).

## Evidencia en Código y Repositorio
- `com.traceability.contracts.authorization.IdentityPrincipalPort.java`.
- `com.traceability.contracts.authorization.AuthorizationPrincipal.java`.
- `com.traceability.contracts.authorization.AuthorizationRole.java`.
- `com.traceability.core.application.authorization.OrganizationBoundaryPolicy.java`.
- `com.traceability.core.application.authorization.RoleAuthorizationPolicy.java`.
- `com.traceability.core.application.authorization.CommandType.java`.
- `com.traceability.core.application.authorization.CrossOrganizationAccessException.java`.
- `com.traceability.core.application.authorization.InsufficientRoleException.java`.
- `core/src/test/java/com/traceability/core/application/command/ExternalActorBypassTest.java`: `@DisplayName("ExternalActor should bypass authorization policies (ADR-032/D6) - Synthetic Test")`.
- `core/src/test/java/com/traceability/core/architecture/ArchitectureTest.java`: `All Command Services must inject RoleAuthorizationPolicy to enforce role-based authorization rules (ADR-032)`.
- Documentos de referencia: `estado-fase5.md` (§9), `plan-ejecucion-agentes-fase5.md` (Bloque 4, Tareas 5.6 a 5.9).

## Consecuencias
### Positivas
- Aislamiento arquitectónico completo entre `core` e `identity` a través de `contracts`.
- Imposibilidad de acceso cruzado entre organizaciones gracias a la guarda centralizada previa.
- Verificación en tiempo de compilación y pruebas de arquitectura para evitar comandos no protegidos.

### Negativas / Restricciones
- Comandos internos adicionales descubiertos con posterioridad (e.g. `requestAllocation`, `deliverAsset`, `confirmAllocation`) requerían evaluación individual antes de su exposición a actores humanos.
