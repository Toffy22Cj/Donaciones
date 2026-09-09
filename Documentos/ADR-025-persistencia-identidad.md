# ADR-025 --- Persistencia de Identidad y Cuentas

**Status:** Proposed\
**Date:** 2026-09-08

## Context

Identidad necesita persistir `Account`, `Organization` y un registro de
auditoría. No necesita reconstruir su estado mediante Event Sourcing.
Esa responsabilidad pertenece a `core`.

MongoDB ya está configurado con Replica Set, por lo que puede
proporcionar transacciones ACID para operaciones que modifican
`Account`, `Organization` y Audit Log.

## Decision

Identidad utilizará **CRUD convencional sobre MongoDB**.

El Audit Log será **append-only**: no tendrá operaciones de
actualización o eliminación expuestas. Solo se registra una entrada
cuando existe una mutación real. Los no-op idempotentes no generan
auditoría.

`changeSummary` contendrá únicamente IDs y roles relevantes. Nunca
almacenará email, password ni PII en texto plano.

No se utilizará hash chain ni Event Sourcing para el Audit Log.

Las operaciones cross-aggregate se ejecutarán en una transacción MongoDB
ACID con `MongoTransactionManager`:

1.  cargar Aggregates;
2.  validar invariantes;
3.  mutar en memoria;
4.  persistir;
5.  registrar Audit Log;
6.  commit.

Si falla cualquier paso, se revierte toda la operación.

Los conflictos transitorios de transacción tendrán un **reintento
acotado de 2--3 intentos**, con backoff mínimo. Cada intento comienza
una nueva transacción y vuelve a cargar el estado antes de reevaluar el
comando.

## Alternatives

### Event Sourcing

Descartado: no existe una necesidad de dominio que lo justifique en
Identidad.

### Outbox/Saga

Descartado para las operaciones internas de Identidad: la transacción
ACID resuelve directamente la consistencia requerida.

### Audit Log fuera de la transacción

Descartado porque permitiría mutaciones sin rastro de auditoría.

### Hash chain

Descartado porque no existe un requisito confirmado de prueba
criptográfica de integridad ante terceros.

## Consequences

La solución es simple y atómica, pero los invariantes internos de
`Organization.members` no tienen enforcement equivalente a un `CHECK` de
SQL. Una escritura directa fuera del Aggregate podría romperlos.

Ese riesgo queda explícitamente aceptado para el alcance actual.

## Status

**Proposed --- pendiente de aprobación humana.**
