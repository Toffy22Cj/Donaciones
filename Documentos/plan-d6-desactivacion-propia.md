# Plan D6 — Desactivación de la propia cuenta (con D7 como pregunta para Carlos)

**Estado: BORRADOR. Espera el "plan aprobado" de Manuel**, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). No se escribe código antes (regla 3.4). Carlos aprueba el PR de implementación con Approve en GitHub.
**Fecha:** 2026-10-24 (UTC).
**Contrato:** ADR-050 (APROBADO — Carlos, 2026-10-18): D6, Q3 (a) y los tests 24 y 25 de su definición de hecho. **D7** (reactivación por el administrador de plataforma, test 26) queda **como pregunta abierta para Carlos (§3)**, sin decidirla, porque Carlos solo nombró D6 en su orden del 2026-10-24 y la tarea 4 de ADR-050 (`ADR-050-cuenta-y-sesiones.md:292`) incluye las dos.
**Base:** `develop` `19b4e0f`. Rama: `feat/identity-d6-desactivacion`, desde el `develop` del momento, con su hash.
**Dueño:** Manuel.

## 0. Dependencias

| # | Qué | Efecto |
|---|---|---|
| **B-1** | R-3 + R-2 fusionado: `Account.deactivate(now)` fija `credentialsNotBefore`, y el filtro rechaza los tokens anteriores | D6 lo reutiliza; el test 25 depende de ello |
| **B-2** | Respuesta de Carlos sobre D7 (§3) | Solo decide si D7 entra en este PR o en otro |

D6 no depende de P-R3-3 ni de R-4 ni de R-5.

## 1. Alcance

Dentro (ADR-050 D6):
- `POST /api/v1/account/deactivate` con JWT y `{currentPassword}` (Q3 (a)): la cuenta pasa a `INACTIVE` y fija `credentialsNotBefore` (D4). No se borra nada. Responde `204` y audita `ACCOUNT_DEACTIVATED` (ya existe).
- **Tres excepciones** en las que la cuenta sigue `ACTIVE`**:
  1. administrador de plataforma → 409 (`PlatformAdministratorDeactivationException`, invariante existente);
  2. `REPRESENTATIVE` de una organización → 409 `RepresentativeTransferRequired` (ADR-026, invariante 5);
  3. responsable activo de una convocatoria no cerrada → 409 `ActiveCampaignResponsible`, consultado con `CampaignResponsibilityPort` (ADR-049 D9, con su misma ventana de concurrencia aceptada).
- La membresía de un `EMPLOYEE` o `ADMINISTRATOR` desactivado **no cambia** (D6). La organización lo retira con la operación existente (ADR-049 D8).

Fuera: D7 (§3), la supresión de datos (D10, manual) y desactivar cuentas ajenas (D7: "fuera de este ADR"). Tampoco resuelve H-S18-2 (D6 lo dice).

## 2. Hechos verificados en `develop` `19b4e0f` (y en la rama de R-3 + R-2)

- `DeactivateAccountService.deactivateAccount(actor, accountId)` existe, sin ruta HTTP. Tras R-3 + R-2 usa `ObjectProvider<Clock>` y `deactivate(now)`. No comprueba ni la contraseña, ni si la cuenta es representante, ni si es responsable.
- `ReactivateAccountService.reactivateAccount(actor, accountId)` existe, sin ruta HTTP.
- `Account.deactivate(now)` lanza `PlatformAdministratorDeactivationException` si la cuenta tiene `platformAuthority`. **Esa excepción no tiene mapeo en `app`** (`IdentityApiErrorMappings` no la lista), así que hoy saldría como 500.
- `RepresentativeTransferRequiredException` y `ActiveCampaignResponsibleException` existen y se mapean a 409 (`IdentityApiErrorMappings`).
- `CampaignResponsibilityPort.activeActingRoles(organizationId, accountId)` (`contracts/.../campaign/CampaignResponsibilityPort.java`) lo implementa `convocatoria` (`CampaignResponsibilityQuery`) y lo usa `identity` en `OrganizationMemberManagementService.remove`. Ese método es el patrón a seguir: primero el representante (409 del dominio) y después la convocatoria (`:104-110`).
- `PasswordHasherPort.matches` existe. `CurrentPasswordIncorrectException` llega con R-3 + R-2.
- `PlatformCommandType` está en `identity` (`identity/.../application/authorization/PlatformCommandType.java`), **no en `contracts`** como dice la fila M-11 de `auditoria-pendientes-manuel-2026-10-24.md:43`. Es relevante solo para D7. Se corrige con una nota en esa auditoría en el PR de este plan.

## 3. D7 — pregunta abierta para Carlos (no se decide aquí)

ADR-050 D7: `POST /api/v1/platform/accounts/{accountId}/reactivate`, con un `PlatformCommandType` nuevo `REACTIVATE_ACCOUNT`. Responde `200 {accountId, status}` y audita `ACCOUNT_REACTIVATED`. Los tokens anteriores siguen rechazados (D4). Test 26: solo el administrador de plataforma reactiva; cualquier otro recibe el 403 uniforme.

**Pregunta para Carlos:** ¿D7 entra en el mismo PR que D6, en un PR propio justo después, o se pospone?

Lo que hay que saber para decidir:
- **Sin D7,** una persona que se desactiva por error no puede volver por la API. El administrador de plataforma solo podría reactivarla fuera del sistema, con `ReactivateAccountService` desde código o tocando la base, lo que no deja auditoría con su actor.
- **D7 es pequeño:**
  - un valor de `PlatformCommandType`;
  - una ruta en `app` con la política de plataforma existente;
  - reutiliza `ReactivateAccountService`;
  - el test 26 y su mutación.
- **Si Carlos dice "mismo PR",** este plan se amplía con una sección D7 que aprueba Manuel antes del código.

Este plan no implementa D7 sin esa respuesta.

## 4. Cambios por módulo

### `identity`

| Archivo | Cambio | Fuente |
|---|---|---|
| `application/service/DeactivateAccountService.java` | Método nuevo `deactivateOwnAccount(AuthorizationPrincipal principal, String currentPassword)` (§4.1). Recibe `PasswordHasherPort`, `OrganizationRepositoryPort`, `CampaignResponsibilityPort` y `AuthorizationAuditActorMapper`. El método existente `deactivateAccount(actor, accountId)` no cambia | D6, Q3 (a) |

#### 4.1 `deactivateOwnAccount`, en una transacción con `executeWithRetry`

1. Cargar la cuenta del principal.
2. **Contraseña actual:** si no coincide → `CurrentPasswordIncorrectException`, **400, nunca 401** (ADR-043 T-1, como en D5) **[Plan, P-D6-1: ADR-050 D6 no fija el código]**.
3. **Administrador de plataforma** → `PlatformAdministratorDeactivationException` (409). La lanza `Account.deactivate(now)`, pero se comprueba **antes** que las otras dos para dar un orden estable **[Plan, P-D6-2]**.
4. Con organización:
   - si es `REPRESENTATIVE` → `RepresentativeTransferRequiredException` (409);
   - si no, `responsibilities.activeActingRoles(org, cuenta)` no vacío → `ActiveCampaignResponsibleException` (409).
   - Es el mismo orden que en `remove`.
5. `account.deactivate(now)`, que fija `credentialsNotBefore`; `save`; auditar `ACCOUNT_DEACTIVATED` con el actor del principal (`AuthorizationAuditActorMapper`). Si ya estaba `INACTIVE`, no se llega aquí: el filtro ya habría dado 401.
6. **No toca la organización ni la membresía** (D6).

### `app`

| Archivo | Cambio | Fuente |
|---|---|---|
| `web/account/AccountDeactivationController.java` (nuevo) **[Plan: nombre]** | `POST /api/v1/account/deactivate`, JWT, `{currentPassword}`. Vacío → 400 sin llamar al dominio. Éxito → `204`. Nunca registra la contraseña. Sin `Command-Id` (DH-34) | D6 |
| `web/IdentityApiErrorMappings.java` | `PlatformAdministratorDeactivationException` → **409** (hoy no está mapeada) **[Plan, P-D6-3: con `title` derivado, `PlatformAdministratorDeactivation`]** | D6 (1) |
| `PublicRoutesInventoryIntegrationTest` | La ruta nueva, protegida | Inventario |

Sin cambios en `contracts`, `core`, `convocatoria` ni `api`.

## 5. Comportamiento ante fallos

| Situación | Resultado |
|---|---|
| Contraseña actual incorrecta | 400 `CurrentPasswordIncorrect`; la cuenta sigue `ACTIVE`, sin `credentialsNotBefore` nuevo y sin auditoría |
| Administrador de plataforma, representante o responsable activo | 409 con su título; la cuenta sigue `ACTIVE` y nada cambia |
| Éxito | `204`; la siguiente petición con cualquier token anterior da 401, también el de la petición |
| Responsable asignado en paralelo a la desactivación | La ventana de concurrencia aceptada en ADR-049 D9 |

## 6. Tests (rojos primero)

| Test | Módulo | Cubre |
|---|---|---|
| Integración de `identity` (Testcontainers, `CampaignResponsibilityPort` simulado) | `identity` | Las tres excepciones, con la cuenta `ACTIVE` y sin auditoría (24); contraseña incorrecta; éxito con `credentialsNotBefore` fijado, la membresía intacta y `ACCOUNT_DEACTIVATED` con el actor de la cuenta |
| `AccountDeactivationHttpIntegrationTest` (nuevo) **[Plan: nombre]** | `app` | 24 por HTTP (con `convocatoria` real para el responsable); 25 (desactivar → la siguiente petición con su token da 401); campo vacío → 400; sin JWT → 401; la contraseña no aparece en los logs |
| `GoldenPathHttpIntegrationTest` | `app` | En verde |

## 7. Mutaciones

| # | Mutación | La mata |
|---|---|---|
| 1 | No comprueba la contraseña actual | Contraseña incorrecta |
| 2 | Sin la comprobación del representante | 24 (representante) |
| 3 | Sin la consulta de responsables | 24 (responsable) |
| 4 | Desactivar quita la membresía | "Membresía intacta" |
| 5 | La contraseña incorrecta da 401 | El de 400 |
| 6 | `PlatformAdministratorDeactivationException` sin mapeo (500) | 24 (administrador, por HTTP) |

## 8. Pasos y evidencia

1. Esperar B-1; con la respuesta de Carlos sobre D7 (B-2), ampliar o no.
2. Rama desde el `develop` del momento.
3. Rojo con la salida literal; verde por capas (`identity`, `app`); mutaciones.
4. Documentos: `referencia-api-v1.md` (la ruta, los tres 409 y el 400) y `estado-fase6.md`.
5. `free -g` con al menos 4 GB y `ci-local.sh` en un clon limpio; autoauditoría contra D6, Q3 y los tests 24 y 25; PR con Carlos.

## 9. Puntos para la aprobación de Manuel

| # | Qué | Recomendación |
|---|---|---|
| **P-D6-1** | Contraseña actual incorrecta → 400 `CurrentPasswordIncorrect` | Aprobar: es lo de D5, y un 401 cerraría la sesión del móvil |
| **P-D6-2** | Orden: contraseña, administrador, representante, responsable | Aprobar: la contraseña primero, como D5; después el orden de `remove` |
| **P-D6-3** | Mapear `PlatformAdministratorDeactivationException` a 409 | Aprobar |
| **P-D6-4** | Nombres marcados **[Plan]** | Aprobar o renombrar |
| **D7** | Pregunta para Carlos (§3) | No se decide aquí |

## 10. Riesgos

- **Nuevo, solo registrado:** sin D7, una desactivación por error no tiene vuelta atrás por la API (§3).
- **R-R3-1** (sin límite de intentos) se repite aquí: con un token robado se pueden probar contraseñas en `/account/deactivate`. Mismo tratamiento: pregunta para Carlos.
- **ADR-049 D9:** la ventana de concurrencia entre asignar un responsable y desactivar la cuenta está aceptada.
