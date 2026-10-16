# Ficha S-18 — Lectura del estado de verificación de la propia organización

**Naturaleza:** ficha breve de contrato HTTP, en el formato de `fichas-encargo4-organizaciones.md`. **No es un ADR, no es normativa y no autoriza código.**
**Estado: CONGELADO** — Q-S18-1 a Q-S18-4 respondidas por Manuel (responsable de backend/BD e identidad) el 2026-10-16T03:23Z; registro en §8. Congelado **no** autoriza código: falta el plan de implementación aprobado (§9).
**Fecha:** 2026-10-16 (UTC).
**Autor del borrador:** Manuel (backend/BD, identidad), con asistencia de Claude.
**Origen:** `paxfide-web` → `Documentos/solicitudes-backend.md`, fila **S-18** (ABIERTA): *"El representante solo ve 'pendiente de verificación' en la respuesta de la creación; después no sabe si se verificó ni qué información se le pidió"*. La pantalla `/panel/organization` muestra hoy "No disponible".
**Reglas comunes:** las de `referencia-api-v1.md` §0. Identity no usa `Command-Id` (DH-34, DD-56); esta ficha es solo lectura. Toda negativa de permiso, otra organización o recurso inexistente en rutas protegidas da el mismo 403 (DD-01).

---

## 1. Problema que resuelve

Tras `POST /organizations` (R9, DD-68), la organización nace `PENDING_VERIFICATION`. El administrador de plataforma puede verificarla, rechazarla o pedir información (`/platform/organizations/{id}/verify|reject|request-information`). La organización **no tiene ninguna forma de enterarse**:

- `GET /me` (ficha N1 congelada) devuelve exactamente `accountId`, `organizationId`, `roles` y `platformAuthority`; no incluye el estado.
- La cola `GET /platform/organizations` sí devuelve `verificationStatus` e `informationRequest`, pero solo al administrador de plataforma.
- DD-68 registró explícitamente: *"no hay `GET /organizations/{id}` (ni lo pide la autorización)"*.

Consecuencia operativa: un representante no puede saber si ya puede crear convocatorias (precondición `VERIFIED`, ADR-037 §5 / ADR-038 §2.5) ni qué le pidió la plataforma.

## 2. Fuentes aplicables

| Fuente | Estado | Qué aporta |
|---|---|---|
| `ADR-038` §2.5 y §2.6 | Aprobado | Estados `PENDING_VERIFICATION`, `NEEDS_MORE_INFORMATION`, `VERIFIED`, `REJECTED`; `Organization` mantiene el estado, el caso de uso consumidor decide; `InformationRequestMessage` (≤ 2000 code points) |
| `ADR-026` / `ADR-027` | Aprobados | `Organization` y sus miembros en el módulo `identity`; frontera `identity → contracts` |
| `ADR-041` §2.1, §2.5 | Aprobado | `api`/`app` traducen, no deciden; errores solo para excepciones nombradas |
| `fichas-encargo4-organizaciones.md` §3.1 (DD-68, DD-69) | Decisiones delegadas registradas | Formato de `name`/`type`; la cola expone `informationRequest`; "sin miembros ni emails" |
| Ficha N1 (`GET /me`) | CONGELADA | No se modifica en esta ficha (ver Q-S18-1) |
| `solicitudes-backend.md` (web) S-18 | ABIERTA | Necesidad del consumidor |
| Código `develop` `15af37f` | [IMPL], solo informativo | `Organization.getVerificationStatus()`, `getVerificationInformationRequest()`; `OrganizationMembersQuery` como precedente de lectura y autorización |

## 3. Contrato propuesto

| Endpoint | Auth | Entrada | Respuesta | Errores |
|---|---|---|---|---|
| `GET /organizations/{organizationId}` → `GET /api/v1/organizations/{organizationId}` | JWT + miembro de esa organización con el rol de Q-S18-2 | Path `organizationId` | `200 {organizationId, name?, type, verificationStatus, informationRequest?}`, `Cache-Control: no-store` | 401 (sin JWT, inválido, expirado o cuenta `INACTIVE`); 403 (otra organización, inexistente o rol insuficiente: el mismo, DD-01) |

### 3.1 Campos de la respuesta

| Campo | Tipo | Regla |
|---|---|---|
| `organizationId` | string | Siempre presente |
| `name` | string | Opcional (`campo?`): el dominio lo admite nulo (DD-04) aunque por HTTP es obligatorio desde DD-68; una organización antigua podría no tenerlo |
| `type` | `"FOUNDATION"` \| `"COMPANY"` | Siempre presente; literales del enum `OrganizationType` |
| `verificationStatus` | `"PENDING_VERIFICATION"` \| `"NEEDS_MORE_INFORMATION"` \| `"VERIFIED"` \| `"REJECTED"` | Siempre presente; literales del enum `VerificationStatus` (pasan a ser contrato público) |
| `informationRequest` | string | Presente **solo** si `verificationStatus = NEEDS_MORE_INFORMATION` (el dominio lo pone a `null` al verificar o rechazar). Sujeto a Q-S18-3 |

**Excluido a propósito:** miembros (ya están en `GET /organizations/{id}/members`), correos, datos de cuentas, historial de verificación, identidad del administrador de plataforma que decidió.

### 3.2 Dominio

**Sin reglas nuevas.** Lectura sin transacción sobre `Organization`, como `OrganizationMembersQuery` y `OrganizationVerificationQueueQuery`. No cambia ningún aggregate, puerto ni evento. No toca el Event Store (identity no es event-sourced).

## 4. Lo que esta ficha NO decide

- Un canal para que la organización **responda** a la petición de información (ver H-S18-1).
- Qué ocurre con la cuenta del representante de una organización `REJECTED` (ver H-S18-2).
- Edición de `name` o `type` de la organización.
- Fotos de la organización (ADR propio pendiente).
- La pantalla `/panel/organization` en `paxfide-web`: es una tarea web aparte, posterior a este endpoint.

## 5. Preguntas abiertas

### Q-S18-1 — Ruta nueva o ampliar `GET /me`

> **Respondida (2026-10-16T03:23Z, Manuel): (a).** Ver §8.

| Opción | Ventaja | Inconveniente |
|---|---|---|
| **(a) `GET /organizations/{organizationId}` nueva** (recomendada) | No reabre la ficha N1 congelada; recurso coherente con `/organizations/{id}/members`, `/funds`, `/physical-assets`; extensible (fotos, edición futura) | Una petición más para el panel |
| (b) Añadir `verificationStatus` a `GET /me` | Una sola petición | Reabre una ficha **CONGELADA** (N1: "exactamente" 4 campos) y mezcla datos de la cuenta con datos de la organización; `/me` se usa en cada carga |

Contradice DD-68 en un punto que hay que registrar: DD-68 dijo que no existía `GET /organizations/{id}`; con (a) pasa a existir. No es una contradicción de diseño (DD-68 solo constató que no lo pedía la autorización), pero hay que anotarlo.

### Q-S18-2 — Quién puede leerlo

> **Respondida (2026-10-16T03:23Z, Manuel): (a).** Ver §8.

| Opción | Consecuencia |
|---|---|
| **(a) `REPRESENTATIVE` o `ADMINISTRATOR`** (recomendada) | Igual que `GET …/members` (`OrganizationMembersQuery`); el `EMPLOYEE` recibe 403. Precedente directo, sin política nueva |
| (b) Cualquier miembro (incluye `EMPLOYEE`) | El empleado vería el texto de `informationRequest`, que va dirigido a la gestión de la organización |
| (c) Solo `REPRESENTATIVE` | Es quien responde ante la plataforma, pero un `ADMINISTRATOR` no podría saber si ya puede crear convocatorias (operación exclusiva de `ADMINISTRATOR`, ADR-037 §5) |

### Q-S18-3 — ¿Se expone el texto de `informationRequest` a la organización?

> **Respondida (2026-10-16T03:23Z, Manuel): (a).** Ver §8.

La cola de plataforma ya lo expone al administrador de plataforma. Ninguna fuente dice si su destinatario es la organización.

| Opción | Consecuencia |
|---|---|
| **(a) Sí, tal cual** (recomendada) | Es el sentido de pedir información; sin él, `NEEDS_MORE_INFORMATION` no es accionable |
| (b) No; solo el estado | La organización sabe que le piden algo pero no qué; obliga a un canal externo |

Implica una regla operativa que hay que comunicar a los administradores de plataforma: **el mensaje lo lee la organización**, así que no debe contener notas internas.

### Q-S18-4 — ¿Endpoint también para el administrador de plataforma?

> **Respondida (2026-10-16T03:23Z, Manuel): (a).** Ver §8.

Un administrador de plataforma **sin** membresía en la organización recibiría 403 con Q-S18-2 (a).

| Opción | Consecuencia |
|---|---|
| **(a) No** (recomendada) | El administrador de plataforma ya tiene la cola; las organizaciones `VERIFIED`/`REJECTED` no aparecen en ella, pero no hay necesidad registrada de leerlas |
| (b) Sí | Requiere un `PlatformCommandType` nuevo (p. ej. `READ_ORGANIZATION`) y una política combinada; amplía el alcance |

## 6. Hallazgos registrados (fuera del alcance; no se resuelven aquí)

- **H-S18-1 — `NEEDS_MORE_INFORMATION` sin canal de respuesta.** La organización no tiene ninguna operación para entregar la información pedida ni para volver a `PENDING_VERIFICATION`. El estado **sí tiene salida** (la plataforma puede verificar o rechazar desde él, `Organization.verify()`/`reject()`), así que no es un bloqueo técnico; DD-69 dice que la verificación documental va por fuera del sistema. Se registra como hueco de producto.
- **H-S18-2 — Cuenta del representante atrapada tras `REJECTED`.** `REJECTED` es terminal (`verify`, `reject` y `requestInformation` lanzan `InvalidVerificationTransitionException`). El representante no puede salir: `removeMemberFromOrganization` lanza `RepresentativeTransferRequiredException` y la transferencia no tiene ruta HTTP (DD-66). Tampoco puede crear otra organización (`AccountAlreadyBelongsToOrganizationException`). Resultado: la cuenta queda sin vía de salida, lo que choca con la regla 2.6 de `reglas-equipo-y-agentes.md` ("todo estado necesita una vía de salida explícita"). Requiere decisión de producto y, probablemente, enmienda de ADR-038 o ADR-026. **No se corrige en esta ficha.**

## 7. Tests previstos (rojos primero, cuando haya plan aprobado)

Test de integración HTTP con Testcontainers (nombre a fijar en el plan), sobre el reactor completo:

1. Representante de una organización recién creada → 200 con `PENDING_VERIFICATION` y sin `informationRequest`.
2. Tras `request-information` → `NEEDS_MORE_INFORMATION` y el texto exacto; tras `verify` → `VERIFIED` y **sin** `informationRequest` (el dominio lo borra).
3. Tras `reject` → `REJECTED`.
4. `ADMINISTRATOR` de la organización → 200 (si Q-S18-2 = a).
5. `EMPLOYEE` de la organización, miembro de **otra** organización, cuenta sin organización, `organizationId` inexistente y administrador de plataforma sin membresía → **el mismo 403**, con aserción de que el cuerpo no distingue los casos.
6. Sin JWT, JWT inválido, cuenta `INACTIVE` → 401.
7. Cabecera `Cache-Control: no-store`.
8. La respuesta tiene **exactamente** los campos de §3.1 (sin miembros, correos ni ids de cuentas).
9. Actualizar `PublicRoutesInventoryIntegrationTest` si el inventario de rutas lo exige (ruta protegida, no pública).

Definición de hecho: `scripts/ci-local.sh` en verde sobre el último commit del PR, con el resumen literal de Surefire pegado en el PR.

## 8. Decisiones

Decididas por **Manuel**, responsable de backend/BD e identidad, el **2026-10-16T03:23Z**, por confirmación explícita en la conversación de trabajo.

| Pregunta | Decisión | Consecuencia que se registra |
|---|---|---|
| Q-S18-1 | **(a)** Crear `GET /api/v1/organizations/{organizationId}` | La ficha N1 (`GET /me`) queda intacta. **Actualización de DD-68:** DD-68 registró que no existía `GET /organizations/{id}` porque no lo pedía la autorización; con esta ficha pasa a existir. No es una contradicción de diseño |
| Q-S18-2 | **(a)** `REPRESENTATIVE` o `ADMINISTRATOR` de esa organización; el `EMPLOYEE` recibe 403 | Misma regla que `OrganizationMembersQuery`; sin política nueva. El `ADMINISTRATOR` puede saber si ya puede crear convocatorias |
| Q-S18-3 | **(a)** `informationRequest` tal cual, solo cuando `verificationStatus = NEEDS_MORE_INFORMATION` | **Tarea derivada (no de código):** comunicar a los administradores de plataforma que la organización lee ese mensaje y que no debe contener notas internas |
| Q-S18-4 | **(a)** Sin acceso adicional para administradores de plataforma | Sin `PlatformCommandType` nuevo; un administrador de plataforma sin membresía recibe el mismo 403 |

Fuera de esta ficha y sin decidir aquí: H-S18-1, H-S18-2 y el hallazgo posterior H-S18-3 (las invitaciones y la gestión de miembros no comprueban el estado de verificación), que se tratarán en una revisión conjunta de 12 puntos si el equipo la aprueba.

## 9. Criterio de cierre

1. ~~Q-S18-1 a Q-S18-4 respondidas y registradas en §8.~~ Hecho (2026-10-16T03:23Z).
2. Plan de implementación aprobado (rama `feat/identity-s18-estado-organizacion`).
3. Tests de §7 rojos, luego verdes, con `ci-local.sh` completo.
4. `referencia-api-v1.md` y `solicitudes-backend.md` (web) actualizados: S-18 → RESUELTA con el commit.
5. H-S18-1 y H-S18-2 trasladados a la lista de pendientes del equipo.
