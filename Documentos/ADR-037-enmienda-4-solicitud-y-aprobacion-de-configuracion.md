# ADR-037 — Enmienda 4: edición de la configuración con solicitud y aprobación (D3)

## Status

**APROBADA como decisión delegada — 2026-10-07T23:59Z** (autorización (3) de Carlos, §3.5: "Requiere una enmienda de ADR-037. Redáctala como decisión delegada siguiendo D2/D3 ya decididos … y luego impleméntala"). `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` **DD-73**.

No reabre nada de la Enmienda 1. Concreta su §3.2 (D3), que dejó "nombre, campos y colección" a la implementación, y añade las reglas mínimas que hacían falta para exponerla por HTTP.

## Context

### Ya decidido y no se reabre

Enmienda 1, §3.2, aprobada:
- **Configuración versionada con efecto prospectivo.** Cada cambio aprobado crea una versión nueva y solo rige hacia adelante. Ningún cambio altera donaciones ni `DonationIntent` anteriores.
- **Antes de la primera donación** (sin `DonationIntent`, H1): edición directa por un `ADMINISTRATOR`, auditada. Ya implementada en `ConvocatoriaLifecycleService.editConfiguration`, sin ruta HTTP.
- **Después:** solo por **solicitud de cambio**, aprobada por **otro `ADMINISTRATOR` o por el `REPRESENTATIVE`, siempre distinto del solicitante**. Sin aprobador válido, el cambio se bloquea. PaxFide nunca aprueba.
- Una convocatoria `CLOSED` no admite cambios.
- La solicitud vive en `convocatoria` como CRUD con registro de auditoría (N3).
- **Concurrencia:** escritura condicional sobre la versión esperada. La solicitud guarda la versión sobre la que se pidió; si al aprobarla la configuración ya avanzó, **la aprobación falla**.
- Ya en el código: `reconfigure` rechaza cambiar la meta, la política, `onTargetReached` y la moneda con `MonetaryTermsChangeNotSupported`. Esto se mantiene.

### Lo que faltaba

- Las rutas HTTP.
- La forma de la solicitud y sus estados.
- Quién puede rechazarla.
- Qué ocurre si la aprobación quitara `MONETARY` cuando ya hay dinero.

## Decision

### D1. Edición directa por HTTP

`POST /api/v1/campaigns/{campaignRef}/configuration`, con `Command-Id` y cuerpo `{expectedConfigurationVersion, configuration}` (la `configuration` de CV-01). Responde `200 {campaignRef, configurationVersion}`. Solo `ADMINISTRATOR` de la organización.

Errores:
- 409 `CampaignAlreadyHasDonations` si ya hay intenciones: hay que usar una solicitud;
- 409 `ConfigurationVersionConflict`, `CampaignClosed` (`ConfigurationChangeOnClosedCampaign`) y `MonetaryTermsChangeNotSupported`;
- 400 por validación de campos.

### D2. La solicitud de cambio (`ConfigurationChangeRequest`)

| Campo | Contenido |
|---|---|
| `requestId` | ULID |
| `campaignRef`, `organizationRef` | |
| `baseConfigurationVersion` | la versión sobre la que se pidió |
| `proposedConfiguration` | la configuración completa propuesta |
| `requestedBy`, `requestedAt` | |
| `status` | `PENDING` → `APPROVED` \| `REJECTED` |
| `decidedBy`, `decidedAt`, `resultingConfigurationVersion` | |

Colección `configuration_change_requests`. **Una sola `PENDING` por convocatoria** (DD-73): una segunda solicitud con otra pendiente da 409 `ConfigurationChangeRequestAlreadyPending`. Así ninguna aprobación queda ambigua y la convocatoria nunca tiene dos propuestas sobre la misma versión.

- **Crear:** `POST /api/v1/campaigns/{campaignRef}/configuration-change-requests`, con `Command-Id` y `{expectedConfigurationVersion, configuration}`. Responde `201 {requestId, status: "PENDING", baseConfigurationVersion}`. Lo hace un `ADMINISTRATOR` de la organización.
  - La versión esperada debe ser la vigente; si no, 409 `ConfigurationVersionConflict`.
  - Convocatoria `CLOSED` → 409.
  - La propuesta se valida al crearla con las mismas reglas que `reconfigure`, sin aplicarla; un cambio de meta o de moneda → 409 `MonetaryTermsChangeNotSupported` desde el principio.
  - **Se puede pedir aunque todavía no haya donaciones** (DD-73): la vía con aprobación nunca es menos segura que la directa.
- **Listar:** `GET /api/v1/campaigns/{campaignRef}/configuration-change-requests`, para `ADMINISTRATOR` o `REPRESENTATIVE`. Responde `200 {items: [{requestId, status, baseConfigurationVersion, proposedConfiguration, requestedBy, requestedAt, decidedBy?, decidedAt?, resultingConfigurationVersion?}]}`, una página de 50, las más recientes primero.
- **Aprobar:** `POST …/configuration-change-requests/{requestId}/approve`, con `Command-Id`. Pueden un `ADMINISTRATOR` o el `REPRESENTATIVE` de la organización, **distinto del solicitante** (si es el mismo → 403 `SelfApprovalNotAllowed`). En una transacción:
  1. la solicitud está `PENDING`;
  2. la convocatoria está `OPEN`;
  3. la versión vigente es igual a `baseConfigurationVersion` (si no, 409 `ConfigurationVersionConflict` y la solicitud sigue `PENDING`, para que alguien la rechace);
  4. se aplica `reconfigure` con escritura condicional de la versión, y se crea el ledger si se añade `MONETARY`;
  5. la solicitud pasa a `APPROVED` con escritura condicional.

  Responde `200 {requestId, status: "APPROVED", configurationVersion}`.
- **Rechazar:** `POST …/{requestId}/reject`, con las mismas personas que aprobar: otro `ADMINISTRATOR` o el `REPRESENTATIVE`. Además, **el propio solicitante puede retirarla** (DD-73: retirar la propia propuesta no cambia la configuración). Responde `200 {requestId, status: "REJECTED"}`. Si ya no está `PENDING` → 409 `ConfigurationChangeRequestNotPending`.

### D3. Sin aprobador válido, bloqueo

La aprobación solo la acepta una persona distinta del solicitante con rol `ADMINISTRATOR` o `REPRESENTATIVE`. Si no existe ninguna (por ejemplo, el único administrador también es el representante y es quien pidió el cambio), la solicitud queda `PENDING` sin que nadie pueda aprobarla: **el cambio se bloquea**, como manda la Enmienda 1. No hay escalado a la plataforma (PaxFide nunca aprueba). El solicitante puede retirarla.

### D4. No se quita `MONETARY` cuando ya hay dinero (regla nueva, restrictiva)

Si la convocatoria tiene intenciones de donación, una configuración que quite `MONETARY` da **409 `MonetaryRemovalNotAllowed`**, por cualquiera de las dos vías. Motivo: borrar el `CampaignFundingLedger` perdería `clearedAmount` y conservarlo sin `MONETARY` dejaría datos sin dueño en la configuración. Sin intenciones, la edición directa sigue retirando el ledger como hasta ahora (N2, G1).

### D5. Auditoría y lo que no cambia

- Acciones nuevas del registro de `convocatoria`: `CONFIGURATION_CHANGE_REQUESTED`, `CONFIGURATION_CHANGE_APPROVED` y `CONFIGURATION_CHANGE_REJECTED`, con `requestId` y versiones, sin la configuración completa.
- `CommandType` nuevos: `REQUEST_CONFIGURATION_CHANGE`, `APPROVE_CONFIGURATION_CHANGE` y `REJECT_CONFIGURATION_CHANGE`, con la idempotencia de T-33 revisado.
- Sin eventos de `core`, sin cambios de esquema de eventos y sin tocar la cadena.

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Varias solicitudes pendientes a la vez | Solo una podría aprobarse (la versión base cambia con la primera); el resto quedaría muerto |
| Escalar a la plataforma si no hay aprobador | La Enmienda 1 lo prohíbe ("PaxFide nunca aprueba") |
| Rebasar la solicitud a la versión nueva al aprobar | La Enmienda 1 manda que la aprobación falle |
| Permitir quitar `MONETARY` conservando el ledger | Deja dinero contabilizado bajo una configuración que no lo admite |

## Consequences

- El panel puede editar antes de la primera donación y pedir o aprobar cambios después, sin intervención del agente.
- Una organización con un solo administrador que además es el representante no puede cambiar la configuración tras la primera donación. Es la consecuencia aceptada de D2 de la Enmienda 1.

## Definición de hecho (tests, rojos primero)

1. Edición directa por HTTP sin donaciones → versión + 1; con una intención → 409 `CampaignAlreadyHasDonations`.
2. Solicitud → `PENDING`; una segunda → 409; con versión vieja → 409; cambio de meta → 409; un `EMPLOYEE`, otra organización o una convocatoria inexistente → el mismo 403.
3. Aprobar el propio solicitante → 403; otro `ADMINISTRATOR` → `APPROVED`, versión + 1, la nueva configuración vigente en CV-07; el `REPRESENTATIVE` también puede.
4. Si la configuración avanzó, la aprobación falla con 409 y la solicitud sigue `PENDING`.
5. Rechazar → `REJECTED`; aprobar una rechazada → 409; el solicitante puede retirar la suya.
6. Quitar `MONETARY` con intenciones → 409 `MonetaryRemovalNotAllowed`, por las dos vías.
7. Convocatoria `CLOSED` → 409 al pedir y al aprobar.
8. La intención creada antes de la aprobación conserva su `configurationVersion`.
9. Los 19 criterios del golden path siguen en verde.
