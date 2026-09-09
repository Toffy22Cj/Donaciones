# ADR-026 --- Modelo de Dominio de Identidad

**Status:** Proposed\
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

## Status

**Proposed --- pendiente de aprobación humana.**
