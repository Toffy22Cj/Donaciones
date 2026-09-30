# ADR-038 — Identidad: HumanActor, Platform Administrator, Verificación de Organization, Autenticación

**Estado:** Approved — diseño conceptual. Decisiones de §7 aprobadas por el equipo el 2026-09-30. **Texto pendiente de revisión humana antes de commit.** Implementación no iniciada.
**Fecha:** Fase 6. Review formal de 12 puntos (Modo de Arquitectura) posterior a ADR-037 (Convocatoria); cierre de huecos 2026-09-30.
**Complementa:** `identity-resumen.md`. No reabre `Account`/`Organization`/`Membership`/`IdentityPrincipalPort`/`OrganizationBoundaryPolicy`/`RoleAuthorizationPolicy` (Fase 4/5).
**Enmienda asociada:** `ADR-026-enmienda-desactivacion-platform-administrator.md` (guarda de `DeactivateAccount`).
**Evidencia del cierre:** `verificacion-adr-038.md` (código real en commit `9c05824`, código fuente de spring-data-mongodb 4.4.4 y mongo-java-driver 5.2.1, manual oficial de MongoDB, verificador de intercalaciones `modelcheck.py`).

> **Nota de numeración.** Versiones previas de este documento circularon como "ADR-034 (número tentativo)". El número definitivo es **ADR-038**. ADR-034 es `read-model-pending-allocation`.

---

## 1. Contexto

Fase 6 requiere:
- que una `Organization` pueda verificarse antes de operar (precondición ya consumida por `CreateConvocatoria`, ADR-037 §5);
- que exista una autoridad de plataforma capaz de verificar organizaciones y gestionar administradores globales;
- que los seis `CommandType` organizacionales (`Fund`/`PhysicalAsset`) puedan atribuirse a un actor humano concreto, sin que `identity` decida autorización de dominio.

Este ADR formaliza el perímetro de Platform Administrator (`Account.platformAuthority` + `PlatformAuthorityState`), la máquina de estados de verificación de `Organization`, los puertos de autenticación y el actor del Audit Log de `identity`.

## 2. Decisión

### 2.1 Actor humano en `core`: `HumanActor` (ADR-035, sin cambios)

La variante humana de `ActorRef` es la ya aprobada e implementada en ADR-035: `HumanActor(accountId)`. Este ADR **no** la redefine. Verificado en código (commit `9c05824`): `HumanActor` ya está conectado a la autorización de `FundCommandService` y `PhysicalAssetCommandService`, con tests de integración propios.

- Una versión anterior de este documento la llamaba `HumanAccount` y le añadía `organizationId` y roles efectivos. **Retirado**: ADR-035 rechazó explícitamente inyectar la identidad completa en el actor de `core`, porque duplicaría lógica del Bounded Context de Identity.
- El contexto organizacional de un actor en un momento dado no se captura en `HumanActor`. Si una auditoría lo necesita, se consulta en el Audit Log de `identity`.
- Límite de construcción (sin cambios): `identity` nunca construye ni importa `HumanActor`. Lo construye `app`, a partir del `accountId` del `AuthorizationPrincipal` ya resuelto vía `IdentityPrincipalPort`.
- `HumanActor` se usa solo para los seis `CommandType` organizacionales. Nunca para acciones de Platform Administrator, que no tocan `core`.

### 2.2 Actor del Audit Log de `identity`

**Situación real (verificada en código):**
- `AuditLogEntry.actorAccountId` es un `AccountId` obligatorio. No puede representar un actor de sistema (el bootstrap).
- Los servicios de Fase 4 registran como actor **a la propia cuenta objetivo**, porque no reciben un actor.

**Decisión:**
- `identity` tiene un tipo de actor propio, cerrado, con dos variantes: **cuenta** (`accountId` de la cuenta autenticada que ejecutó la acción) y **sistema** (identificador fijo del proceso interno; hoy solo el bootstrap).
- No reutiliza `core.domain.event.ActorRef`, `SystemActor` ni `HumanActor`: sería una dependencia `identity → core`, prohibida.
- Todo Application Service de `identity` que escribe en el Audit Log recibe el actor como parámetro obligatorio. Ninguno lo deduce del objetivo.
- Nombres exactos de clase y de campos: detalle de implementación, sujeto a revisión de PR.

**Régimen histórico (ADR-025: el Audit Log es *append-only*):**
- **Las entradas existentes no se migran ni se reescriben.**
- **Pre-corte**: entradas escritas antes del cambio de esquema. Su campo de actor **no constituye evidencia fiable** de quién ejecutó la acción, porque el código que las produjo registraba el objetivo como actor. Se leen, pero con esa semántica declarada.
- **Post-corte**: entradas escritas con el nuevo modelo. Su actor sí tiene semántica contractual.
- El régimen se distingue por **la forma del documento persistido** (presencia del nuevo campo de actor), no por comparación de fechas. El lector expone explícitamente a qué régimen pertenece cada entrada.
- La **fecha de corte** se registra en este ADR al fusionar `fix/identity-audit-actor`: fecha y commit de merge, como dato documental.
- **La discriminación por forma es un contrato de la rama `fix/identity-audit-actor`, no una suposición.** Esa rama debe demostrar con tests:
  - un documento con la forma antigua se lee como **pre-corte**;
  - un documento con la forma nueva se lee como **post-corte**;
  - un documento antiguo **nunca** se interpreta como actor contractual (aserción negativa explícita);
  - el código nuevo **nunca** escribe un documento con la forma antigua.
- Alcance real del régimen pre-corte (verificado 2026-09-30): ningún código de producción invoca hoy los servicios de `identity`. Las entradas pre-corte solo pueden existir en bases de desarrollo o de test.

### 2.3 Platform Administrator

- `Account.platformAuthority: PlatformAuthority?`, con un único valor hoy: `ADMINISTRATOR`. Es autoridad global, en un eje paralelo a `Membership.roles`.
- **Invariante**: todo `Account` con `platformAuthority` está en estado `ACTIVE` (se mantiene mediante §2.3.2 y la enmienda a ADR-026). Solo así `activeAdministratorCount` significa lo que su nombre dice.
- Cinco `PlatformCommandType`, cerrados para MVP: `VERIFY_ORGANIZATION`, `REJECT_ORGANIZATION`, `REQUEST_ORGANIZATION_INFORMATION`, `GRANT_PLATFORM_AUTHORITY`, `REVOKE_PLATFORM_AUTHORITY`.
- Los cinco requieren `platformAuthority == ADMINISTRATOR`, evaluado por `PlatformAuthorizationPolicy` (`identity.application.authorization`).
- `activePlatformAdmins >= 1` en todo momento. La auto-revocación está permitida si queda al menos otro administrador.

#### 2.3.1 `PlatformAuthorityState`

- Documento **singleton** con `_id = "platform-authority"` y campos `{ activeAdministratorCount, version }`.
- **Por qué existe** (verificado): sin este documento, dos `REVOKE` concurrentes que cuentan sobre `accounts` dejan cero administradores en 250 de 252 intercalaciones (*write skew*). Con él, 0 contraejemplos.
- `version` se incrementa en cada cambio, **solo como dato de diagnóstico**. No es mecanismo de control. Coherente con ADR-026, que excluye optimistic locking por versión en `identity`.

#### 2.3.2 `GRANT` y `REVOKE`

Ambos modifican `Account` + `PlatformAuthorityState` + Audit Log en **una sola transacción MongoDB** (`MongoTransactionManager` canónico de `app`). Todas las precondiciones se evalúan **dentro** de la transacción.

**`GrantPlatformAuthority(targetAccountId)`**, en orden:
1. `PlatformAuthorityState` inexistente → `PlatformAuthorityStateMissingException`.
2. Cuenta destino inexistente → `AccountNotFoundException`.
3. Cuenta destino `INACTIVE` → `InactiveAccountException`. **Nuevo en el cierre**: sin esta regla se puede crear un administrador inactivo contado en el contador, lo que abre un bloqueo (verificado).
4. Cuenta destino ya con `platformAuthority` → `PlatformAuthorityAlreadyGrantedException`.
5. Escritura condicional sobre `Account` (solo si no tiene autoridad) y `$inc: +1` sobre el singleton.
6. Entrada de Audit Log.

**`RevokePlatformAuthority(targetAccountId)`**, en orden:
1. `PlatformAuthorityState` inexistente → `PlatformAuthorityStateMissingException`.
2. Cuenta destino inexistente → `AccountNotFoundException`.
3. Cuenta destino sin `platformAuthority` → `PlatformAuthorityNotHeldException`.
4. `updateOne({_id: "platform-authority", activeAdministratorCount: {$gt: 1}}, {$inc: -1})`. Si `matched == 0` → `LastPlatformAdministratorException`.
5. Escritura condicional sobre `Account` (solo si tiene autoridad).
6. Entrada de Audit Log.

**Casos redundantes (#1/#2): excepción determinista, no no-op.** Sin mutación y sin entrada de Audit Log. Difiere deliberadamente del precedente de ADR-026, donde `AssignAdministrator` es no-op idempotente: `GRANT`/`REVOKE` mueven un contador global, y un no-op exigiría demostrar que no lo toca en ninguna rama.

**Mecanismo de concurrencia (#9):**
- **Mecanismo primario**: todo `GRANT`/`REVOKE` exitoso modifica el mismo singleton. Dos operaciones concurrentes cualesquiera entran en conflicto de escritura; la perdedora se reintenta completa, relee y **reevalúa** sus precondiciones.
- **Por qué debe modificar siempre**: según el manual de MongoDB, una transacción solo adquiere el bloqueo de un documento si su escritura lo modifica. Una operación que no cambia el contador no serializa nada.
- **Defensa en profundidad**: las escrituras condicionales sobre `Account` de los pasos 5 no son necesarias para la corrección del diseño (verificado: resultados idénticos con y sin ellas). Protegen contra un error de implementación que evalúe la precondición fuera de la transacción; en ese caso, sin ellas, el contador se infla en 6.538 de 15.780 intercalaciones, y con ellas, 0.
- **Concurrencia `GRANT` + `REVOKE` sobre la misma cuenta (#3)**: no hay regla de precedencia propia. Decide el orden de commit; la perdedora reevalúa y, si su precondición ya no se cumple, recibe la excepción determinista correspondiente.

#### 2.3.3 Estado ausente (#8)

- Si `PlatformAuthorityState` no existe, `GRANT` y `REVOKE` fallan con `PlatformAuthorityStateMissingException` (**fail-closed**).
- **Sin auto-reparación**: recontar sobre `accounts` reintroduce el *write skew* que el singleton existe para evitar.
- La autorización de lectura (`PlatformAuthorizationPolicy` lee `Account.platformAuthority`) no se ve afectada.
- La reconciliación es una operación manual documentada, fuera del MVP.

### 2.4 Bootstrap (#5, #6)

Establece el primer Platform Administrator fuera del flujo normal de comandos. **Nunca invoca `GrantPlatformAuthority`**: es imposible que el primer administrador se autorice mediante una política que exige que ya exista uno.

- **Activación**: runner de un solo uso en `app`, habilitado solo con `traceability.bootstrap.platform-admin.enabled=true` más el email de la cuenta destino (variable de entorno).
- **Sin credenciales en configuración**: la cuenta se crea por el registro normal; el bootstrap solo le otorga autoridad.
- **Precondiciones** (fail-closed, sin ninguna escritura si fallan):
  1. `PlatformAuthorityState` no existe; si existe → `PlatformAlreadyBootstrappedException`.
  2. **Ninguna** cuenta tiene `platformAuthority`; si alguna la tiene → `PlatformAuthorityInconsistentStateException`. Sin esta regla, un estado ausente con administradores previos acabaría en `count = 1` con varios administradores reales (verificado), y el bootstrap sería una auto-reparación encubierta.
  3. La cuenta destino existe y está `ACTIVE`.
- **Atomicidad**: una sola transacción inserta el singleton con `count = 1`, asigna `Account.platformAuthority` y escribe la entrada de Audit Log.
- **Guarda contra doble ejecución**: el `_id` fijo del singleton. Dos bootstraps concurrentes → exactamente uno gana (verificado).
- **Fallo de arranque**: si el flag está activo y el bootstrap falla por cualquier precondición, la aplicación no arranca. Obliga al operador a retirar el flag o corregir el estado.
- **Auditoría**: acción `BOOTSTRAP_PLATFORM_AUTHORITY`, con la variante **sistema** del actor de `identity` (§2.2) y la cuenta destino. No se fabrica ningún actor humano.

### 2.5 Verificación de `Organization`

Máquina de estados (`identity-resumen.md` §4), sin modificación:

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

- **Transición no definida (#4)**: cualquier comando de verificación sobre `VERIFIED` o `REJECTED` → `InvalidVerificationTransitionException(currentStatus, command)`, sin mutación ni entrada de Audit Log.
- `Organization` expone su estado; la decisión la toma el caso de uso consumidor (por ejemplo, `CreateConvocatoria` exige `VERIFIED`).

### 2.6 Contratos de aplicación (#12, #13)

Application Service en `identity`. Cada método recibe el `AuthorizationPrincipal` ya resuelto, evalúa `PlatformAuthorizationPolicy` **antes** de cualquier lectura de negocio, y retorna `void`:

| Comando | Campos |
|---|---|
| `VerifyOrganization` | `organizationId` |
| `RejectOrganization` | `organizationId` |
| `RequestOrganizationInformation` | `organizationId`, `message` |
| `GrantPlatformAuthority` | `targetAccountId` |
| `RevokePlatformAuthority` | `targetAccountId` |

- Ninguna firma acepta `platformAuthority` ni roles como argumento del llamador.
- `message`: se eliminan los espacios de los extremos; vacío tras eso → `InvalidInformationRequestMessageException`; **más de 2000 caracteres → misma excepción. Se rechaza, no se trunca**: truncar alteraría en silencio lo que se pide a la organización. El valor 2000 es decisión de producto (2026-09-30), sin fuente técnica.

### 2.7 Autenticación

```
AuthenticateAccountPort.authenticate(email, password) → accountId | authentication failure
IdentityPrincipalPort.resolvePrincipal(accountId)     → AuthorizationPrincipal | InactiveAccountException
TokenIssuerPort.issue(accountId)                      → token | TokenIssuanceException
```

- **Firma corregida**: el método real es `resolvePrincipal` (`contracts/.../IdentityPrincipalPort.java`), no `resolve` como decía la versión previa.
- **`authenticate()`**: email inexistente, contraseña incorrecta y cuenta `INACTIVE` producen la misma respuesta externa indistinguible (evita enumeración de cuentas). Cubre la indistinguibilidad lógica/HTTP, no el tiempo de respuesta constante.
- **`resolvePrincipal()`**: cuenta `INACTIVE` → `InactiveAccountException`. **Estado real verificado: la implementación actual no lo hace** (devuelve un principal completo). Se corrige en `fix/identity-resolve-principal-inactive` **antes** de cualquier trabajo de JWT.
- **`issue()` (#7)**: fallo de emisión → `TokenIssuanceException`, que se traduce a HTTP 500, nunca 401 (401 contaminaría la indistinguibilidad del login). Sin reintento automático; el login no escribe nada que compensar.
- **JWT mínimo**: `sub/iat/exp/firma`, sin `organizationId`/roles/`platformAuthority`/email. `AuthorizationPrincipal` se resuelve en cada request autenticada.
- **Cambio de contrato (reglas §3.5)**: añadir `platformAuthority` a `AuthorizationPrincipal` modifica un tipo de `contracts` consumido por `core`. El PR que lo introduzca debe verificar la compilación y los tests de todos los módulos consumidores, no solo de `identity`.

### 2.8 Reintentos transaccionales (#11)

- **`TransientTransactionError`**: se reintenta la **transacción completa**, con un máximo de **3 intentos** (valor fijo en código, no configurable).
- **Espera entre intentos**: *backoff* exponencial con *jitter*, **encapsulado en el helper de reintento**, con límites explícitos en código (base, factor, tope). Hoy el helper reintenta sin espera; eso hace probable agotar los intentos mientras la otra transacción sigue abierta.
- **Intentos agotados** → `ConcurrentModificationException` (excepción nombrada de `identity`). Nunca la `MongoException` cruda.
- **`UnknownTransactionCommitResult`** (fallo ambiguo): se reintenta **solo el commit**, nunca el comando de negocio. Base verificada:
  - spring-data-mongodb 4.4.4 ignora por defecto las etiquetas del commit y ofrece el gancho `doCommit` para esto;
  - el driver 5.2.1 deja la sesión en estado que admite un nuevo `commitTransaction()`.
  - Si tras esos reintentos sigue indeterminado → `PlatformAuthorityOutcomeUnknownException`; el cliente consulta el estado antes de repetir.
- Parámetros exactos (base, factor, tope, intentos de commit): se fijan en el PR y se documentan como política técnica en el mismo helper.

## 3. Consecuencias

**Positivas:**
- Los 13 huecos de la versión previa quedan cerrados con decisión explícita y evidencia.
- Se hace explícito el invariante "todo Platform Administrator está `ACTIVE`" y se protege desde los dos lados (`GRANT` y `DeactivateAccount`).
- El Audit Log pasa a registrar actores reales, sin reescribir la historia.

**Negativas / deuda aceptada:**
- La reconciliación de un `PlatformAuthorityState` ausente es manual.
- Las entradas pre-corte del Audit Log quedan con un actor no fiable, de forma permanente y declarada.
- La corrección exige cambios en código de Fase 4 ya cerrado (tres ramas `fix/`, §7).

## 4. Alternativas descartadas

- **`identity` construye el actor humano de `core`**: introduciría `identity → core`.
- **Reutilizar `SystemActor`/`ActorRef` de `core` en el Audit Log de `identity`**: misma razón.
- **Redefinir `HumanActor` con organización y roles**: contradice ADR-035 (aprobado e implementado).
- **No-op idempotente para `GRANT`/`REVOKE` redundantes**: ver §2.3.2.
- **Auto-reparar `PlatformAuthorityState` recontando cuentas**: reintroduce el *write skew*.
- **Bootstrap que solo comprueba la cuenta destino**: produce un contador incorrecto si ya existían administradores (verificado).
- **Migrar o reescribir las entradas pre-corte del Audit Log**: viola el *append-only* de ADR-025 y convierte una atribución falsa en un dato aparentemente legítimo.
- **Truncar el mensaje de solicitud de información**: altera el contenido en silencio.

## 5. Autorización

- Cinco comandos de Platform → `PlatformAuthorizationPolicy`, un solo escalón, sin `OrganizationBoundaryPolicy`.
- Bootstrap → mecanismo estructuralmente distinto, no gobernado por `PlatformAuthorizationPolicy` (§2.4).
- `authenticate()`/`resolvePrincipal()`/`issue()` → sin `RoleAuthorizationPolicy`: producen el principal que las políticas consumen.

## 6. Observabilidad

Cadena `actor → comando → escritura (Account/Organization) → PlatformAuthorityState (si aplica) → Audit Log`, con el actor propio de `identity` (§2.2). El bootstrap queda dentro de la cadena, con la variante **sistema** del actor.

## 7. Cierre de los huecos de la versión previa

| # | Hueco | Resolución | Sección |
|---|---|---|---|
| 1 | `GRANT` sobre cuenta ya administradora | `PlatformAuthorityAlreadyGrantedException` | §2.3.2 |
| 2 | `REVOKE` sobre cuenta sin autoridad | `PlatformAuthorityNotHeldException` | §2.3.2 |
| 3 | `GRANT` + `REVOKE` concurrentes | Orden de commit + reevaluación | §2.3.2 |
| 4 | Verificación sobre `VERIFIED`/`REJECTED` | `InvalidVerificationTransitionException` | §2.5 |
| 5 | Mecanismo del bootstrap | Runner de un solo uso, precondición global, fail-fast | §2.4 |
| 6 | Auditoría del bootstrap | Variante sistema del actor de `identity` | §2.2, §2.4 |
| 7 | Fallo de `issue()` | `TokenIssuanceException` → 500 | §2.7 |
| 8 | Estado ausente | Fail-closed, sin auto-reparación | §2.3.3 |
| 9 | Mecanismo de concurrencia | Singleton como mecanismo primario; escritura condicional como defensa en profundidad | §2.3.2 |
| 10 | Singleton | `_id = "platform-authority"` | §2.3.1 |
| 11 | Reintentos | 3 intentos + backoff/jitter; `ConcurrentModificationException`; solo re-commit ante resultado ambiguo | §2.8 |
| 12 | Firmas | Tabla de §2.6 | §2.6 |
| 13 | `RequestOrganizationInformation` | `message` no vacío, máx. 2000, rechazo | §2.6 |
| A | Desactivar a un Platform Administrator | Enmienda a ADR-026 | Enmienda asociada |
| B | `GRANT` sobre cuenta `INACTIVE` (nuevo) | `InactiveAccountException` | §2.3.2 |

**Trabajo derivado sobre código existente (no forma parte de este ADR; cada rama con test de regresión que falle antes de la corrección):**
- `fix/identity-resolve-principal-inactive` — §2.7. Precede a cualquier trabajo de JWT.
- `fix/identity-audit-actor` — §2.2. Incluye la lectura compatible de las entradas pre-corte.
- `fix/identity-deactivate-retry` — los 7 servicios con `@Transactional` sin reintento, contra lo que exigen ADR-026 y `estado-fase4.md`.

## 8. Trazabilidad de verificación

Evidencia completa en `verificacion-adr-038.md`. Por la política de red de la sesión de verificación (Docker Hub y Maven Central bloqueados), **no se ejecutó MongoDB real ni `mvn test`**. Los resultados del verificador de intercalaciones son de un modelo de la semántica documentada de MongoDB, no del motor. La *Definition of Done* de la implementación son los tests con Testcontainers listados en la §6 de ese informe, con la salida literal de Surefire.
