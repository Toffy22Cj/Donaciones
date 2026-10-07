# ADR-026 --- Modelo de Dominio de Identidad

**Status:** Approved with amendment (aprobado 2026-09-08 según `estado-fase4.md` §1; enmienda 2026-09-30)\
**Date:** 2026-09-08

## Context

Identidad necesita representar cuentas, organizaciones, membresías y
roles sin multi-tenancy.

Decisiones congeladas:

-   una `Account` pertenece a cero o una `Organization`;
-   `Organization` es el tipo paraguas;
-   tipos actuales: `FOUNDATION` y `COMPANY`;
-   `GOVERNMENT` / entidad pública queda fuera de Fase 4;
-   roles: `REPRESENTATIVE`, `ADMINISTRATOR`, `EMPLOYEE`;
-   `ADMINISTRATOR` es opcional;
-   cada `Organization` tiene exactamente un `REPRESENTATIVE`;
-   la transferencia del representante es explícita;
-   nunca existe `Membership.roles = {}`.

## Decision

Se definen dos Aggregates:

``` text
Account
├── accountId: AccountId
├── email: Email
├── passwordHash: PasswordHash
├── status: AccountStatus
└── organizationId: OrganizationId?

Organization
├── organizationId: OrganizationId
├── type: OrganizationType
└── members: List<Membership>

Membership
├── accountId: AccountId
└── roles: Set<Role>
```

Ser miembro significa tener una entrada en `members`. Una membresía debe
tener al menos un rol y puede tener varios.

### Value Objects

-   `AccountId` --- ULID opaco.
-   `Email` --- validado y único.
-   `PasswordHash` --- hash opaco; el dominio no conoce el algoritmo.
-   `AccountStatus` --- `ACTIVE | INACTIVE`.
-   `OrganizationId` --- ULID opaco.
-   `OrganizationType` --- `FOUNDATION | COMPANY`.
-   `Role` --- `REPRESENTATIVE | ADMINISTRATOR | EMPLOYEE`.

### Comandos

#### Account

-   `CreateAccount(email, passwordHash)` --- crea sin organización;
    email único.
-   `ChangeCredentials(accountId, newPasswordHash)` --- cuenta activa.
-   `DeactivateAccount(accountId)` --- cuenta activa.
-   `ReactivateAccount(accountId)` --- cuenta inactiva.

#### Organization

-   `CreateOrganization(type, initialRepresentativeAccountId)` --- la
    cuenta existe y no pertenece a otra organización; nace con un
    representante.
-   `AddEmployee(accountId)` --- cuenta sin organización; crea
    `Membership{EMPLOYEE}` y fija `organizationId`.
-   `AssignAdministrator(accountId)` --- requiere membresía previa; si
    ya es administrador, es no-op idempotente.
-   `RemoveAdministrator(accountId)` --- si deja la membresía sin roles,
    rechaza con `CannotRemoveLastRoleException`.
-   `RemoveEmployee(accountId)` --- si deja la membresía sin roles,
    rechaza con `CannotRemoveLastRoleException`.
-   `RemoveMemberFromOrganization(accountId)` --- elimina la membresía y
    limpia `organizationId`; si contiene `REPRESENTATIVE`, rechaza con
    `RepresentativeTransferRequiredException`.
-   `TransferRepresentativeAndRemove(currentRepresentativeAccountId, newRepresentativeAccountId)`
    --- el sucesor debe ser miembro y distinto del actual; transfiere el
    rol y, si el saliente queda sin roles, elimina su membresía y limpia
    `organizationId` dentro de la misma operación.

`TransferRepresentative` sin remoción queda descartado.

`JoinOrganization` queda descartado: la incorporación ocurre mediante
`AddEmployee`.

### Invariantes

1.  Una cuenta pertenece como máximo a una organización.
2.  Toda organización nace con exactamente un representante.
3.  Toda organización válida tiene exactamente un `REPRESENTATIVE`.
4.  Nunca existe una membresía con roles vacíos.
5.  El representante no puede ser removido directamente.
6.  La transferencia exige sucesor miembro y distinto.
7.  Quitar el último rol mediante `RemoveAdministrator` o
    `RemoveEmployee` es rechazado.
8.  La transferencia puede eliminar explícitamente la membresía del
    representante saliente como parte de la misma operación atómica.
9.  Los roles pueden coexistir.
10. `AssignAdministrator` es idempotente.

### Excepciones

`DuplicateEmailException`, `AccountNotFoundException`,
`OrganizationNotFoundException`,
`AccountAlreadyBelongsToOrganizationException`,
`AccountNotMemberOfOrganizationException`,
`CannotRemoveLastRoleException`,
`RepresentativeTransferRequiredException`,
`TransferTargetNotMemberException`, `SelfTransferNotAllowedException`.

### Audit Log

Acciones cerradas:

``` text
ACCOUNT_CREATED
CREDENTIALS_CHANGED
ACCOUNT_DEACTIVATED
ACCOUNT_REACTIVATED
ORGANIZATION_CREATED
REPRESENTATIVE_TRANSFERRED
ADMINISTRATOR_ASSIGNED
ADMINISTRATOR_REMOVED
EMPLOYEE_ADDED
EMPLOYEE_REMOVED
MEMBER_REMOVED
```

Se elimina `ACCOUNT_JOINED_ORGANIZATION` porque no existe
`JoinOrganization`.

`changeSummary` no contiene PII en texto plano.

### Concurrencia

Se utilizan transacciones ACID de MongoDB. Los errores transitorios se
reintentan 2--3 veces; cada intento recarga los Aggregates dentro de una
nueva transacción. No se introduce optimistic locking por versión.

## Alternatives

### Tres listas paralelas

Descartadas porque no expresan estructuralmente la membresía y permiten
divergencias entre roles.

### Membership con roles vacíos

Descartada porque introduce un estado sin propósito de negocio.

### `TransferRepresentative` separado

Descartado: `TransferRepresentativeAndRemove` cubre ambos casos.

### Eliminación automática del último rol

Descartada para `RemoveAdministrator`/`RemoveEmployee`. La salida normal
debe ser explícita; la transferencia es la excepción explícitamente
definida.

## Consequences

El modelo unificado elimina ambigüedades y mantiene las invariantes
dentro de `Organization`. MongoDB no puede garantizar por esquema el
representante único dentro del array, por lo que una escritura que evite
el Aggregate podría producir un estado inválido.

## Enmienda — `DeactivateAccount` sobre una cuenta con `platformAuthority`

La decisión original de ADR-026 (comandos, invariantes y excepciones de `Account` y `Organization`) queda **aprobada sin cambios**, salvo en un punto: el comportamiento de `DeactivateAccount(accountId)` cuando la cuenta tiene autoridad de plataforma (`Account.platformAuthority`, introducida por ADR-038).

### Problema

ADR-038 exige `activePlatformAdmins >= 1` y lo protege con el contador `PlatformAuthorityState.activeAdministratorCount`. `DeactivateAccount`, tal como está hoy, no conoce `platformAuthority`. Permite desactivar a un administrador de plataforma sin tocar el contador:

- El contador sigue contándolo como administrador.
- La cuenta ya no puede autenticarse.
- Resultado verificado: tras `REVOKE(A)` y `DEACTIVATE(B)`, con dos administradores iniciales, queda `count = 1` y ningún administrador activo. **La plataforma queda sin administración.** Ocurre en el 100 % de las intercalaciones y no requiere concurrencia: basta con ejecutarlos en ese orden.

### Decisión

1. **Nueva precondición de `DeactivateAccount`**: si la cuenta tiene `platformAuthority`, se rechaza con `PlatformAdministratorDeactivationException`, sin mutación ni entrada de Audit Log.
2. **Vía de salida explícita**: para desactivar a un administrador de plataforma, primero se revoca su autoridad con `RevokePlatformAuthority` (ADR-038), que ya protege `activePlatformAdmins >= 1`. Después se desactiva normalmente.
3. **Ubicación de la regla**: en el Aggregate `Account` (`deactivate()`), porque `status` y `platformAuthority` son estado del mismo Aggregate.
4. **Nuevo invariante de `Account`**: *una cuenta con `platformAuthority` está siempre `ACTIVE`*. Lo mantienen conjuntamente esta enmienda (no se desactiva un administrador) y ADR-038 §2.3.2 (no se otorga autoridad a una cuenta `INACTIVE`).
5. **Concurrencia**: `DeactivateAccount` y `GrantPlatformAuthority` sobre la misma cuenta escriben el mismo documento `Account`, así que entran en conflicto de escritura y la perdedora reevalúa. Esto **solo funciona si `DeactivateAccount` reintenta** (ADR-026, §Concurrencia). Hoy no lo hace: se corrige en `fix/identity-deactivate-retry`, que debe fusionarse antes o junto con esta regla.

### Nueva excepción

`PlatformAdministratorDeactivationException` — se añade a la lista de excepciones de ADR-026.

**Ubicación**: `identity.domain.exception`, junto a las otras 12 excepciones del módulo. Es el patrón existente, verificado en código (commit `9c05824`): `identity` no tiene excepciones de capa de aplicación, y `Account` ya lanza excepciones de ese paquete (`InactiveAccountException`, `AccountAlreadyBelongsToOrganizationException`). El Aggregate no importa nada de la capa de aplicación; la regla de ArchUnit del módulo (`domain` no depende de Spring ni de MongoDB) se mantiene. El nombre describe la regla de dominio (no se desactiva a un administrador de plataforma), no el caso de uso.

### Sin cambios

- `ReactivateAccount`: no se ve afectado. Una cuenta inactiva nunca tiene `platformAuthority`, por el invariante del punto 4.
- La idempotencia de `DeactivateAccount` sobre una cuenta ya `INACTIVE` (no-op, implementada en Tarea 4.2) se mantiene.

### Tests exigidos

- Unitario de `Account`: `deactivate()` sobre cuenta con `platformAuthority` → excepción; estado sin cambios.
- Integración (Testcontainers): `DeactivateAccount` sobre un administrador de plataforma → excepción, cuenta intacta, contador intacto, **cero** entradas nuevas de Audit Log.
- Integración: `RevokePlatformAuthority` seguido de `DeactivateAccount` → ambos exitosos, contador decrementado una sola vez.
- Concurrencia (`CyclicBarrier`): `GrantPlatformAuthority(C) || DeactivateAccount(C)` → al final, ninguna cuenta con `platformAuthority` está `INACTIVE`.

**Implementada** en los commits `db046ba` (test rojo) y `3146e75` (rama `feat/identity-adr-038`, 2026-10-01). Concurrencia `GrantPlatformAuthority(C) ‖ DeactivateAccount(C)` cubierta en `PlatformAuthorityConcurrencyIntegrationTest` (`4234723`).

## Status

**Approved with amendment.** Aprobado el 2026-09-08 (`estado-fase4.md` §1). Enmienda de desactivación de un Platform Administrator aprobada el 2026-09-30 (ADR-038, hueco A).
