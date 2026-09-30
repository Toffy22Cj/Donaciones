# Enmienda a ADR-026 — Desactivación de una cuenta con autoridad de plataforma

**Insertar esta sección al final de `ADR-026-modelo-dominio-identidad.md`, antes de `## Status`, y cambiar el estado del documento a "Approved with amendment".**

**Origen:** cierre de huecos de ADR-038 (hueco A), aprobado por el equipo el 2026-09-30. Evidencia en `verificacion-adr-038.md` (escenario S4).

> **Nota sobre el estado actual del archivo.** `ADR-026-modelo-dominio-identidad.md` sigue diciendo
> "Proposed — pendiente de aprobación humana", pero `estado-fase4.md` §1 lo registra como **Approved — 2026-09-08**
> (igual que ADR-025 y ADR-027, que tienen la misma discrepancia). Al aplicar esta enmienda, el estado pasa a
> "Approved with amendment" citando esa fecha. ADR-025 y ADR-027 no se tocan en esta enmienda: su discrepancia de
> estado queda anotada para una corrección documental aparte.

---

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
