# Fichas — tercera autorización de Carlos (organizaciones, usuarios, empleados y administradores)

**Origen:** "Autorización de trabajo autónomo (3) — Carlos, 2026-10-07", §3. Una ficha breve por bloque, en el orden de prioridad de Carlos. Las decisiones del agente llevan su número DD (`decisiones-delegadas-2026-10.md` §4).
**Reglas comunes:** las de `referencia-api-v1.md` §0. Identity no usa `Command-Id` (DH-34, DD-56). Toda negativa de permiso, otra organización o recurso inexistente en rutas protegidas da el mismo 403 (DD-01).

---

## 3.1 Crear organización (R9) y cola de verificación

**Cierra:** R9 (`hallazgos-front-fase2.md`) y Q-v2-2 (`propuesta-apis-fase6.md` §3.2). Q-v2-1 se respondió "(a) semilla" el 2026-10-06; la tercera autorización la reabre con la opción (b), y el flujo de producto queda fijado por Carlos: "cualquier usuario registrado; queda como `REPRESENTATIVE`".

| Endpoint | Auth | Entrada | Respuesta | Errores |
|---|---|---|---|---|
| `POST /organizations` | JWT (cualquier cuenta activa) | `{type: FOUNDATION\|COMPANY, name}` | `201 {organizationId, verificationStatus: "PENDING_VERIFICATION"}` | 400 `type` o `name` (vacío o > 200); 401; 409 `AccountAlreadyBelongsToOrganization` |
| `GET /platform/organizations` | JWT + autoridad de plataforma | `?status=PENDING_VERIFICATION\|NEEDS_MORE_INFORMATION` (sin él, las dos), `?cursor=` | `200 {items: [{organizationId, name?, type, verificationStatus, informationRequest?}], nextCursor?}`, 20 por página, `no-store` | 400 `status` o `cursor`; 401; 403 |

**Dominio:** sin reglas nuevas. `CreateOrganizationService` (ADR-026) ya exige que la cuenta no pertenezca a otra organización y crea al `REPRESENTATIVE`. La cola es una lectura nueva (`OrganizationVerificationQueueQuery`) con un comando de plataforma nuevo, `READ_VERIFICATION_QUEUE`, que exige `platformAuthority = ADMINISTRATOR` como los demás.

**Decisiones:**
- **DD-68:**
  - `name` es **obligatorio por HTTP**, aunque el dominio lo admite opcional (DD-04): la cola y CV-07 lo muestran. Se guarda sin espacios a los lados.
  - `type` se valida contra el enum en mayúsculas exactas.
  - La respuesta no incluye `Location`: no hay `GET /organizations/{id}` (ni lo pide la autorización).
- **DD-69:**
  - La cola incluye `PENDING_VERIFICATION` y `NEEDS_MORE_INFORMATION` (las dos esperan a la plataforma). Pedir `VERIFIED` o `REJECTED` → 400.
  - Orden por `organizationId` (ULID, orden de creación).
  - El cursor usa la clave del descubrimiento (DD-58, sin otro secreto) con un **propósito propio dentro del texto cifrado** (`verification-queue:` + ULID): un cursor de una lista no vale en la otra.
  - Sin miembros ni emails: la verificación documental va por fuera del sistema.
- `AccountAlreadyBelongsToOrganizationException` no tenía traducción (habría sido 500). Ahora da 409, también al aceptar una invitación (ADR-049 D4).

**Tests** (`OrganizationRegistrationAndQueueHttpIntegrationTest`, rojos primero):
- crear → `REPRESENTATIVE` y `PENDING_VERIFICATION`; `/me` lo refleja;
- repetir, o hacerlo un miembro de otra organización → 409;
- validaciones → 400 sin efecto; sin JWT → 401;
- 23 pendientes recorridas en ≥ 2 páginas sin duplicados, en orden y sin una verificada;
- filtro `NEEDS_MORE_INFORMATION` con su mensaje; estados no pendientes → 400;
- administrador de organización, representante y cuenta suelta → el mismo 403;
- cursor alterado, ajeno o del descubrimiento → 400, y el de la cola no vale en el descubrimiento.
