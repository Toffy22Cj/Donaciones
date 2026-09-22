# ADR-034 (número tentativo — confirmar contra el catálogo real antes de commitear) — Identidad: HumanAccount, Platform Administrator, Verificación de Organization, Autenticación

**Estado:** Aprobado — diseño conceptual y arquitectónico. Pendiente de implementación, de las decisiones funcionales listadas en §7, y de verificaciones técnicas que no bloquean continuar con las capas siguientes de Fase 6.
**Fecha:** Sesión de Fase 6, review formal de 12 puntos (Modo de Arquitectura), inmediatamente posterior a ADR-033 (Convocatoria).
**Complementa:** `identity-resumen.md`. No reabre `Account`/`Organization`/`Membership`/`IdentityPrincipalPort`/`OrganizationBoundaryPolicy`/`RoleAuthorizationPolicy` (cerrados en Fase 4/5).

---

## 1. Contexto

Fase 6 requiere que una `Organization` pueda verificarse antes de operar (precondición ya consumida por `CreateConvocatoria`, ADR-033 §5), que exista una autoridad de plataforma capaz de verificar organizaciones y gestionar administradores globales, y que los seis `CommandType` organizacionales existentes (`Fund`/`PhysicalAsset`) puedan asociarse a un actor humano concreto para auditoría, sin que eso implique que `identity` decida autorización de dominio.

Este ADR formaliza el perímetro de `HumanAccount`, Platform Administrator (`Account.platformAuthority` + `PlatformAuthorityState`), la máquina de estados de verificación de `Organization`, y los tres puertos de autenticación, todos ya diseñados conceptualmente en `identity-resumen.md` pero sin el review formal de 12 puntos que este ADR provee.

## 2. Decisión

### 2.1 `HumanAccount`

Variante de `ActorRef` (taxonomía ADR-031): snapshot histórico inmutable de `accountId + organizationId + roles efectivos en el momento de la acción`, sin PII ni credenciales, sin `platformAuthority`. Se usa exclusivamente cuando un actor humano dispara uno de los seis `CommandType` organizacionales existentes sobre `Fund`/`PhysicalAsset` — nunca para acciones de Platform Administrator (que no tocan `core`).

**Límite de construcción**: `HumanAccount` es un tipo del lado de `core` (mismo patrón que `ActorRef`/`ExternalActor`). `identity` **nunca lo construye ni lo importa** — quien lo construye es `app`, a partir del `AuthorizationPrincipal` ya resuelto vía `IdentityPrincipalPort`, en el momento de orquestar un comando de `core`. Esto preserva la frontera `identity ↛ core` / `core ↛ identity` ya establecida, con `contracts` como único punto de cruce (`IdentityPrincipalPort`, `AuthorizationPrincipal`).

`HumanAccount` no participa en la autorización de la operación — captura el contexto de autorización que `app` ya recibió para esa ejecución. No introduce noción de versión: un snapshot en `t0` permanece válido como registro histórico aunque los roles del actor cambien después.

### 2.2 Actor propio para el audit log de `identity`

`Account`/`Organization` son CRUD + audit log (no Event Sourced). Su audit log interno **no puede usar `core.domain.event.ActorRef`** sin violar la misma frontera de arriba — mismo patrón ya aplicado en ADR-033 para `ConvocatoriaAuthorizationPolicy` (no reutilizar directamente un tipo de `core` desde un módulo hermano). `identity` usa una representación de actor propia para sus operaciones de auditoría interna; nombre de clase y campos exactos quedan como detalle de implementación.

### 2.3 Platform Administrator

- `Account.platformAuthority: PlatformAuthority?` (único valor hoy: `ADMINISTRATOR`) — autoridad global, eje paralelo a `Membership.roles`, sin solape con `OrganizationBoundaryPolicy`/`RoleAuthorizationPolicy` (comandos globales, sin `organizationRef` de recurso).
- Cinco `PlatformCommandType`, cerrados para MVP: `VERIFY_ORGANIZATION`, `REJECT_ORGANIZATION`, `REQUEST_ORGANIZATION_INFORMATION`, `GRANT_PLATFORM_AUTHORITY`, `REVOKE_PLATFORM_AUTHORITY`. Todos comparten hoy el mismo requisito: `platformAuthority == ADMINISTRATOR`, evaluado por `PlatformAuthorizationPolicy` (vive en `identity.application.authorization`, no en `core` — mismo razonamiento que llevó a `ConvocatoriaAuthorizationPolicy` en ADR-033).
- `activePlatformAdmins >= 1` en todo momento; auto-revocación permitida si queda al menos otro. Protegido por `PlatformAuthorityState { activeAdministratorCount, version }`, documento dedicado — evita el mismo *write skew* que motivó `CampaignResponsibleState` en ADR-033 (dos revocaciones concurrentes sobre cuentas distintas, cada una viendo "hay margen" en su propia lectura).
- `Grant`/`Revoke` modifican `Account` + `PlatformAuthorityState` + Audit Log en la misma transacción MongoDB (reutiliza el `MongoTransactionManager` canónico de `app`, verificado real en ADR-033 §2.3).
- **Mecanismo exacto de concurrencia (escritura condicional atómica vs. optimistic locking con `version`) NO verificado** — a diferencia de `CampaignResponsibleState` en ADR-033, aquí no se tiene código real que inspeccionar; el campo `version` está documentado en la forma conceptual, pero su función efectiva (mecanismo activo, dato auxiliar, o residuo) no puede confirmarse sin ver la implementación. No se recomienda sustituir `version` por el patrón de Convocatoria sin esa evidencia.
- `PlatformAuthorityState` se trata, por inferencia estructural (no confirmada documentalmente), como documento singleton — no correlacionado por clave como `CampaignResponsibleState`, sino único para toda la plataforma.

### 2.4 Bootstrap

Ocurre fuera del flujo normal de comandos/autorización; inicializa `Account.platformAuthority` y `PlatformAuthorityState.activeAdministratorCount=1` de forma coherente; nunca invoca `GrantPlatformAuthority`. **Estructuralmente distinto de cualquier comando protegido por `PlatformAuthorizationPolicy`** — necesidad lógica, no decisión de diseño: es imposible que el primer administrador se autorice a sí mismo mediante una política que exige que ya exista uno. Mecanismo técnico exacto (una sola vez vs. repetible, secreto de despliegue, guardas contra re-ejecución, trazabilidad en el Audit Log) queda explícitamente diferido — ver §7.

### 2.5 Verificación de `Organization`

Máquina de estados (`identity-resumen.md` §4), reproducida sin modificación:

```
PENDING_VERIFICATION → VERIFY → VERIFIED
PENDING_VERIFICATION → REQUEST_INFORMATION → NEEDS_MORE_INFORMATION (+mensaje)
PENDING_VERIFICATION → REJECT → REJECTED
NEEDS_MORE_INFORMATION → VERIFY → VERIFIED
NEEDS_MORE_INFORMATION → REQUEST_INFORMATION → NEEDS_MORE_INFORMATION (self-transition, mensaje reemplazado, no acumulado)
NEEDS_MORE_INFORMATION → REJECT → REJECTED
VERIFIED → sin comandos de verificación en MVP
REJECTED → terminal en MVP (limitación funcional explícita, no permanente por diseño)
```

`Organization` mantiene su `verificationStatus`; la decisión de autorización/precondición la ejecuta el caso de uso consumidor (p. ej. `CreateConvocatoria` valida `VERIFIED` — `Organization` no "decide" quién puede publicar convocatorias, solo expone el estado).

Comportamiento de cualquier comando de verificación sobre `VERIFIED` o `REJECTED` (estados sin transición definida) **no especificado** — ver §7.

### 2.6 Autenticación — tres puertos, firma verificada en la fuente

```
AuthenticateAccountPort.authenticate(email, password) → accountId | authentication failure
IdentityPrincipalPort.resolve(accountId) → AuthorizationPrincipal | InactiveAccountException
TokenIssuerPort.issue(accountId) → token
```

- `authenticate()`: email inexistente, password incorrecto, y cuenta `INACTIVE` producen la **misma respuesta externa indistinguible** (previene enumeración de cuentas) — esto cubre indistinguibilidad lógica/HTTP, **no** garantía de tiempo de respuesta constante (no está en la fuente, no se congela aquí).
- `resolve()`: cuenta `INACTIVE` → `InactiveAccountException`, explícita — contrato distinto de `authenticate()`, no debe unificarse (protege escenarios distintos: enumeración en login vs. sesión ya autenticada que dejó de ser válida).
- `issue()`: comportamiento ante fallo de emisión **no documentado en ninguna fuente** — hueco genuino, ver §7.
- JWT deliberadamente mínimo (`sub/iat/exp/firma`), sin `organizationId`/roles/`platformAuthority`/email/`HumanAccount`. `AuthorizationPrincipal` se resuelve en cada request autenticada, no solo en login.

## 3. Consecuencias

- Positivas: cierra el review formal que faltaba para que Identidad avance a `plan-ejecucion-agentes-fase6.md`; hace explícito por qué `HumanAccount` no puede construirse en `identity` (frontera modular, no convención); identifica que `identity` necesita su propia representación de actor de auditoría, evitando una importación prohibida antes de que ocurra en código.
- Negativas / deuda aceptada: el mecanismo de concurrencia de `PlatformAuthorityState` queda sin verificar (a diferencia de Convocatoria, aquí no hay código real disponible en esta sesión); tres decisiones funcionales genuinas quedan abiertas (§7) sin las cuales no puede implementarse el comportamiento completo de `GRANT`/`REVOKE`/bootstrap.

## 4. Alternativas descartadas

- **`identity` construye `HumanAccount` directamente**: descartada — introduciría `identity → core`, prohibido.
- **Reutilizar `core.domain.event.ActorRef` para el audit log de `identity`**: descartada — misma razón, mismo patrón de corrección que `ConvocatoriaAuthorizationPolicy` en ADR-033.
- **Inventar un `HumanAccount` sintético para trazar el bootstrap en el Audit Log normal**: descartada — contaminaría el significado histórico del actor; el bootstrap, por definición, no tiene un `AuthorizationPrincipal` autenticado que capturar.
- **Tratar `VERIFY`/`REJECT`/`REQUEST_INFORMATION` sobre estados sin transición como rechazo automático por convención**: descartada explícitamente durante el review — sería el test (o el ADR) inventando el contrato que el propio review declaró abierto, en vez de esperar la decisión del equipo.

## 5. Autorización

- Cinco comandos de Platform → `PlatformAuthorizationPolicy`, único escalón (`platformAuthority == ADMINISTRATOR`), sin `OrganizationBoundaryPolicy`.
- Bootstrap → mecanismo de confianza estructuralmente distinto, no gobernado por `PlatformAuthorizationPolicy` (ver §2.4, §7).
- `authenticate()`/`resolve()`/`issue()` → sin `RoleAuthorizationPolicy` (son la infraestructura que produce el `AuthorizationPrincipal` que las demás políticas consumen, no comandos que ellas mismas autoricen).

## 6. Observabilidad

Cadena `actor → command → write (Account/Organization) → PlatformAuthorityState (si aplica) → AuditLog`, con actor propio de `identity` (no `HumanAccount`) para los cinco comandos normales. El bootstrap rompe esta cadena por definición — no genera una entrada de Audit Log con la misma forma que las operaciones normales, porque no existe todavía ningún actor autenticado que capturar; esto es un hueco de trazabilidad real, no solo de autorización (ver §7).

## 7. Explícitamente NO resuelto por este ADR

| # | Pendiente | Tipo | Bloquea implementación de |
|---|---|---|---|
| 1 | `GRANT` sobre `Account` ya `ADMINISTRATOR` — ¿no-op idempotente o excepción determinista? | Producto | Semántica completa de `GrantPlatformAuthority` |
| 2 | `REVOKE` sobre `Account` sin autoridad — ¿no-op idempotente o excepción determinista? | Producto | Semántica completa de `RevokePlatformAuthority` |
| 3 | `GRANT` + `REVOKE` concurrentes sobre la misma cuenta — ¿existe regla de precedencia, o "gana la última transacción confirmada" es aceptable como semántica de producto (no solo como comportamiento del motor)? | Producto | Ese caso límite de concurrencia |
| 4 | Cualquier comando de verificación (`VERIFY`/`REJECT`/`REQUEST_INFORMATION`) invocado sobre `Organization` en `VERIFIED` o `REJECTED` | Producto | Comportamiento completo de la máquina de verificación |
| 5 | Mecanismo técnico exacto del bootstrap (una sola vez vs. repetible, secreto/credencial de despliegue, guardas contra re-ejecución) | Producto/infraestructura — explícitamente diferido por la fuente, no abierto por este ADR | El propio bootstrap |
| 6 | Trazabilidad del bootstrap en el Audit Log de dominio | Producto/diseño — mismo origen que #5 | Auditoría completa de "cuándo y cómo se estableció el primer administrador" |
| 7 | Comportamiento ante fallo de `TokenIssuerPort.issue()` | Producto — hueco no documentado en ninguna fuente | Manejo de errores de emisión JWT |
| 8 | Comportamiento ante ausencia/inconsistencia de `PlatformAuthorityState` (fail-closed / auto-reparación / recuperación administrativa explícita) | Producto | Resiliencia operacional de Platform Administrator |
| 9 | **Mecanismo real de concurrencia de `PlatformAuthorityState`** (condición atómica / optimistic `version` / combinación) y función efectiva de `version` | Verificación técnica — sin código disponible en esta sesión | Implementación de `Grant`/`Revoke` |
| 10 | `PlatformAuthorityState` como documento singleton físico, `_id` fijo | Propuesta estructural de este review, no verificada | Esquema físico de la colección |
| 11 | Comportamiento real de `TransientTransactionError`/retry sin duplicar `Account`/`PlatformAuthorityState`/AuditLog | Verificación técnica | Robustez de la transacción conjunta |
| 12 | Firmas exactas de los cinco comandos de aplicación de Platform (`VerifyOrganization`, etc.) | Verificación de contrato — solo forma plausible propuesta | Materialización del Application Service |
| 13 | Forma exacta de exposición de `RequestOrganizationInformation` en el contrato de aplicación (parámetros, retorno) | Verificación de contrato | Ese comando específico |

**Nota de corrección documental**: una versión intermedia de este review propuso, sin evidencia verificable, que `AuthorizationRole` se traduce desde un tipo interno de `identity` mediante un mapper/ACL explícito con esa función y ese nombre. Retirado — no aparece en `identity-resumen.md` ni en ninguna otra fuente disponible. Lo único que permanece congelado es que `AuthorizationRole` vive en `contracts`, como tipo neutral, distinto de cualquier tipo interno de `identity` o `core` (consecuencia directa de que `contracts` es frontera neutral, no un hecho sobre un mecanismo de mapeo concreto).

Ningún punto de esta tabla bloquea continuar con las capas siguientes de Fase 6 (Blockchain, IA); los marcados "Producto" deben resolverse antes de asignar esa pieza específica a un agente de código, y los de "Verificación técnica" antes de dar por buena la implementación de `PlatformAuthorityState`.

## 8. Trazabilidad de verificación

A diferencia de ADR-033, este ADR **no tuvo código real disponible para inspeccionar** en esta sesión (no hay equivalente a `FundCommandService`/`MongoEventStoreAdapter` para Platform Administrator). Todo lo aquí congelado se apoya en `identity-resumen.md` como única fuente conceptual — las secciones marcadas "no verificado" en §7 (ítems 9-13) requieren específicamente esa misma clase de verificación contra código que sí se hizo para Convocatoria, antes de tratarlas como cerradas.
