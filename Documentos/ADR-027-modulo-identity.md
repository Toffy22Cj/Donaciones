# ADR-027 --- Módulo Maven Independiente `identity`

**Status:** Proposed\
**Date:** 2026-09-08

## Context

El proyecto es un Monolito Modular con fronteras estrictas. `core`
contiene trazabilidad; Identidad contiene cuentas, organizaciones,
membresías y roles. Son bounded contexts distintos.

Mezclar Identidad dentro de `core` contaminaría su ciclo de vida,
dependencias y modelo de dominio.

## Decision

Se crea un módulo Maven independiente llamado **`identity`**.

Estructura:

``` text
identity/
└── src/main/java/identity/
    ├── domain/
    ├── application/
    │   └── port/
    │       ├── in/
    │       └── out/
    └── infrastructure/
```

El módulo utiliza una estructura hexagonal equivalente a `core`.

### Responsabilidad

`identity` contiene:

-   `Account`;
-   `Organization`;
-   `Membership`;
-   Value Objects;
-   comandos y Application Services;
-   puertos de repositorio;
-   Audit Log;
-   adaptadores MongoDB;
-   adaptadores responsables del hashing.

El dominio no conoce Spring, MongoDB, BSON ni anotaciones de
persistencia.

### Boundary explícito

`identity` no modifica, reemplaza, centraliza ni redefine:

-   `donorRef`;
-   `custodianRef`;
-   `beneficiaryRef`;
-   `assetRef`.

`AccountId` / `userId` es un identificador opaco propio de Identidad. Si
otro módulo necesita referenciar una cuenta, deberá existir un contrato
explícito. No se crea ahora una dependencia anticipada.

`contracts` solo se extenderá si aparece una necesidad real de
comunicación entre módulos.

## Alternatives

### Paquete dentro de `core`

Descartado porque mezcla bounded contexts distintos.

### Microservicio independiente

Descartado porque no existe una necesidad demostrada de distribución o
despliegue independiente. El proyecto permanece como Monolito Modular.

### Identidad dentro de `contracts`

Descartado porque `contracts` contiene contratos entre módulos, no
Aggregates ni lógica de dominio.

## Consequences

### Positivas

-   aislamiento del bounded context;
-   ciclo de vida Maven independiente;
-   dependencias futuras de seguridad/hash no contaminan `core`;
-   frontera verificable mediante ArchUnit;
-   preservación del modelo de trazabilidad existente.

### Negativas

-   añade un módulo al reactor;
-   requiere reglas de arquitectura para mantener la frontera;
-   contratos entre módulos solo deberán agregarse cuando exista una
    necesidad real.

## Scope Boundary

Crear `identity` no autoriza:

-   mover referencias de trazabilidad al nuevo módulo;
-   sustituir `donorRef`, `custodianRef`, `beneficiaryRef` o `assetRef`;
-   convertir `Account` en Aggregate de trazabilidad;
-   introducir Event Sourcing en Identidad;
-   introducir Spring Security en el dominio;
-   crear dependencias arbitrarias entre módulos.

## Status

**Proposed --- pendiente de aprobación humana.**
