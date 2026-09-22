# Identidad — Resumen de diseño conceptual (no congelado)

**Estado:** Diseño pre-ADR, sujeto a extensión. Documenta la frontera de identidad y autorización tal como quedó cerrada conceptualmente — no incluye decisiones de Spring Security ni de implementación, deliberadamente, para no documentar una implementación imaginaria.
**Para qué sirve:** punto de continuidad para retomar Capa 2 (Identidad) de Fase 6 sin reconstruir el hilo completo de la conversación.

---

## 1. Lo que ya existía antes de esta conversación (Fase 4/5, cerrado e implementado)

- `Account` y `Organization`: Aggregates Roots, CRUD + Audit Log append-only (sin Event Sourcing, ADR-025/026/027).
- `AccountStatus = ACTIVE | INACTIVE`.
- `Membership.roles` (nunca vacío): `REPRESENTATIVE` (exactamente uno por `Organization` en todo momento), `ADMINISTRATOR` (0..N), `EMPLOYEE` (rol base de incorporación vía `AddEmployee`).
- Pertenencia única: una `Account` pertenece a cero o una `Organization`.
- `IdentityPrincipalPort` (interfaz en `contracts`) + `AuthorizationPrincipal` (DTO): ya resolvía `accountId`, `organizationId?`, `roles` antes de esta conversación.
- `OrganizationBoundaryPolicy` / `RoleAuthorizationPolicy` (`core.application.authorization`, ADR-032) — autorización organizacional sobre `Fund`/`PhysicalAsset`, sin cambios en esta capa.
- BCrypt (`spring-security-crypto`, factor 12) ya presente para `passwordHash` — sin `spring-boot-starter-security` todavía.
- Explícitamente fuera de alcance hasta ahora: cualquier endpoint HTTP, login, sesión.

## 2. `HumanAccount` (decisión previa, confirmada en esta capa)

- Variante de `ActorRef` (taxonomía cerrada en ADR-031): `accountId + organizationId + roles efectivos en el momento de la acción`.
- Snapshot histórico **inmutable**, sin PII ni credenciales — no es la fuente de autorización, solo registra bajo qué contexto se ejecutó la acción.
- Solo se usa para actores humanos autenticados ejecutando los seis `CommandType` organizacionales existentes; los donantes (con o sin cuenta) y los webhooks siguen usando `ExternalActor`, nunca `HumanAccount`.
- No incorpora `platformAuthority`.
- Pendiente, explícitamente diferido: si debe distinguir "actuó por autoridad ordinaria" de "actuó mediante la capacidad de respaldo de `REPRESENTATIVE`" (enmienda ADR-032) — se resolverá cuando se implementen esos comandos, no antes.

## 3. Platform Administrator — nuevo en esta capa

**Fuente de verdad**
```text
Account
└── platformAuthority: PlatformAuthority?   (enum cerrado)

PlatformAuthority
└── ADMINISTRATOR   (único valor hoy)
```
- Autoridad **global**, fuera de `Membership`/`Organization` — un eje de autorización paralelo, no un rol más.
- `HumanAccount` no incorpora esta autoridad.

**Cardinalidad**
- `activePlatformAdmins >= 1` en todo momento — no se puede revocar al último.
- Auto-revocación **permitida**, siempre que quede al menos otro administrador tras la operación.
- Protección mediante documento de estado dedicado: `PlatformAuthorityState { activeAdministratorCount, version }`. Razón técnica: una comprobación tipo `count → check → update` sobre la colección `accounts` sufre *write skew* (dos revocaciones concurrentes sobre cuentas distintas pueden cada una ver "hay margen" y ambas proceder). El contador dedicado fuerza a que ambas operaciones compitan por el mismo documento, lo cual sí serializa correctamente bajo transacción MongoDB.
- `Grant`/`Revoke` deben modificar `Account` + `PlatformAuthorityState` + Audit Log en la misma transacción.

**Bootstrap**
- El primer Platform Administrator se establece fuera del flujo normal de comandos/autorización (mecanismo de despliegue/provisioning, forma técnica exacta sin definir).
- Bootstrap inicializa `Account.platformAuthority` y `PlatformAuthorityState.activeAdministratorCount = 1` de forma coherente.
- Nunca invoca `GrantPlatformAuthority` como comando.

**`PlatformCommandType` (cinco valores, cerrado para MVP)**
```text
VERIFY_ORGANIZATION
REJECT_ORGANIZATION
REQUEST_ORGANIZATION_INFORMATION
GRANT_PLATFORM_AUTHORITY
REVOKE_PLATFORM_AUTHORITY
```
`SUSPEND_ORGANIZATION` queda explícitamente fuera del MVP — su semántica (qué pasa con campañas activas, fondos, reversibilidad) nunca se cerró.

**`PlatformAuthorizationPolicy`** (vive en `identity.application.authorization`, no en `core`, porque protege Aggregates de `identity`)
- `authorize(principal, commandType) → void | InsufficientPlatformAuthorityException`.
- Un solo escalón de evaluación — `OrganizationBoundaryPolicy` no aplica a estos comandos (son globales, sin `organizationRef` de recurso).
- Los cinco comandos comparten hoy el mismo requisito (`platformAuthority == ADMINISTRATOR`); el enum existe por exhaustividad futura, no por diferenciación real actual.
- No conoce estado de `Organization`, contenido de mensajes, ni el contador de administradores — eso vive en las reglas de negocio de `Account`/`Organization` y en el `CommandService`.

## 4. Verificación de `Organization` — nuevo en esta capa

```text
Organization
├── verificationStatus: PENDING_VERIFICATION | NEEDS_MORE_INFORMATION | VERIFIED | REJECTED
└── verificationInformationRequest?: String   (mensaje vigente, reemplazable)
```

Máquina de estados:
```text
PENDING_VERIFICATION → VERIFY → VERIFIED
PENDING_VERIFICATION → REQUEST_INFORMATION → NEEDS_MORE_INFORMATION (+ mensaje)
PENDING_VERIFICATION → REJECT → REJECTED

NEEDS_MORE_INFORMATION → VERIFY → VERIFIED
NEEDS_MORE_INFORMATION → REQUEST_INFORMATION → NEEDS_MORE_INFORMATION (+ mensaje actualizado, self-transition)
NEEDS_MORE_INFORMATION → REJECT → REJECTED

VERIFIED → sin comandos de verificación en MVP
REJECTED → terminal en MVP (limitación funcional explícita, no permanente por diseño)
```

- Solo una `Organization VERIFIED` puede publicar convocatorias y recibir donaciones — precondición nueva sobre lo ya cerrado en Convocatoria.
- Quién ejecutó cada operación queda en el Audit Log; **no** se duplica como campo `verifiedBy` en `Organization` (evita dos fuentes de verdad).
- Modelo MVP: Modelo A (revisión manual del Platform Administrator). Modelo B (verificación automática contra fuentes externas) descartado por alcance del proyecto.

## 5. Autenticación / JWT — nuevo en esta capa

**Tres puertos en `contracts`, tres responsabilidades distintas — no intercambiables:**
```text
AuthenticateAccountPort
    authenticate(email, password) → accountId | authentication failure

IdentityPrincipalPort
    resolve(accountId) → AuthorizationPrincipal | InactiveAccountException

TokenIssuerPort
    issue(accountId) → token
```

- Autenticación: `email` existe **y** `BCrypt.matches(password, passwordHash)` **y** `status == ACTIVE`. Los tres fallos son **indistinguibles** externamente (previene enumeración de cuentas).
- `resolve()` **rechaza explícitamente** una cuenta `INACTIVE` con `InactiveAccountException` — nunca devuelve un `AuthorizationPrincipal` "vacío" (roles={}, platformAuthority=null), porque eso comprimiría estados semánticamente distintos en un objeto ambiguo.
- La resolución de `AuthorizationPrincipal` ocurre **en cada request autenticada**, no solo en login — un cambio de rol, de organización, o una revocación de `platformAuthority` tienen efecto inmediato; una cuenta desactivada queda bloqueada en la siguiente request, sin esperar a que expire el JWT.

**Forma final de `AuthorizationPrincipal`:**
```text
accountId
organizationId?
roles: Set<AuthorizationRole>
platformAuthority: PlatformAuthority?
```

**JWT — mínimo, deliberadamente:**
```text
sub = accountId
iat
exp
signature
```
Nunca contiene: `organizationId`, `roles`, `platformAuthority`, `email`, `HumanAccount`, permisos de negocio.

**Frontera de módulos para la composición HTTP (corregida — ver nota de reversión al final):**
```text
contracts → AuthenticateAccountPort, IdentityPrincipalPort, TokenIssuerPort
api       → LoginController; JWT OncePerRequestFilter (nuevo, junto al ya
            existente para TrackingCode); implementa TokenIssuerPort
identity  → implementa AuthenticateAccountPort e IdentityPrincipalPort
app       → bootstrap puro + MongoTransactionManager; sin capa HTTP propia,
            sin Spring Security
```
`api` ya es el módulo HTTP del proyecto desde Fase 3 (`spring-boot-starter-web`, `OncePerRequestFilter` para el tracking code de ADR-021-A). No se introduce `spring-boot-starter-security` — se extiende el patrón de filtro propio ya existente y probado, en vez de traer un framework cuya parte de mayor valor (autorización declarativa) el proyecto decidió no usar de todas formas.

## 6. Decisiones de módulo/persistencia que siguen valiendo de Convocatoria, sin cambios
- `spring-boot-starter-security` limitado al perímetro HTTP/composición — nunca en `core`, `contracts` ni `identity` (dominio).
- Reutilización del `MongoTransactionManager` canónico ya provisto por `app` (mismo hallazgo que resolvió `STRICT` en Convocatoria) — aplica igual a las transacciones de `Grant`/`RevokePlatformAuthority`.

## 7. Lo que falta / sigue abierto

**Implementación (no bloquea el perímetro conceptual):**
- Algoritmo de firma, duración de `exp`, gestión/rotación de claves — deliberadamente no fijado.
- Estrategia de access/refresh token (relevante por el patrón offline de Flutter, Outbox + `flutter_secure_storage`).
- Mecanismo técnico exacto del bootstrap (deployment/provisioning).
- Test de concurrencia real (`CyclicBarrier`) para `activeAdministratorCount`, con los escenarios ya identificados (revocaciones simultáneas, grant+revoke concurrente, `TransientTransactionError`, fallo entre `Account`/estado/Audit Log, bootstrap).
- Configuración concreta de `SecurityFilterChain`, códigos HTTP (`401`/`403`), mecanismo MVC para exponer `AuthorizationPrincipal` a los controllers.

**Diseño, deliberadamente fuera de este cierre:**
- Lecturas cross-organización del dashboard de Platform Administrator (usuarios, organizaciones, gráficas) — rompe una frontera hoy absoluta (`OrganizationBoundaryPolicy`/`CrossOrganizationAccessException`); necesita su propio tratamiento, probablemente con auditoría reforzada de acceso. No es lo mismo que `PlatformAuthorizationPolicy` (que gobierna comandos, no consultas).
- Perfil de usuario: qué puede editar cada quien, si `fullName` se expone públicamente (pendiente desde Convocatoria).
- Verificación de email (flujo completo: token, expiración, reenvío, cambio de email).
- Flujo de registro/invitación de empleados como caso de uso HTTP completo.
- 2FA (decisión provisional: `ADMINISTRATOR`/Platform Administrator sí, resto no — sin implementar).

## 8. Nota de procedencia

Los cimientos (`Account`, `Organization`, `Membership`, `IdentityPrincipalPort`, `OrganizationBoundaryPolicy`, `RoleAuthorizationPolicy`) ya estaban documentados en Fase 4/5 antes de esta conversación. Todo lo demás — `HumanAccount` (forma final), Platform Administrator completo, verificación de Organization, y los tres puertos de autenticación — es diseño nuevo de esta sesión, sin precedente en los documentos previos del proyecto.

## 9. Nota de reversión — Spring Security

La primera versión de este resumen proponía `spring-boot-starter-security` completo, con su composición viviendo en `app`. Dos correcciones posteriores la revirtieron: (1) `app` nunca ha tenido capa HTTP propia — `api` es el módulo HTTP desde Fase 3; (2) `api` ya resuelve un problema estructuralmente idéntico (autenticación bearer sin `spring-boot-starter-security`) con un `OncePerRequestFilter` propio para el tracking code (ADR-021-A). Decisión revertida: la autenticación JWT se implementa como un segundo `OncePerRequestFilter` en `api`, no como `spring-boot-starter-security`. El mecanismo exacto de propagación de `AuthorizationPrincipal` a controllers/Application Services queda pendiente — es ya nivel de implementación, deliberadamente pospuesto hasta que el resto de Fase 6 esté planeado a nivel conceptual.
