# ADR-037 — Enmienda 6: el `REPRESENTATIVE` puede leer el listado de convocatorias de su organización

## Status

**PROPUESTO — espera aprobación.** Redactada el 2026-11-15 por Manuel, con asistencia de Claude, a partir de su decisión del mismo día («quiero que pueda elegir la convocatoria, porque si no la predicción le es inalcanzable»; opción (a) de D1, elegida al aprobar `plan-paso1-fixes-prediccion-2026-11-15.md`). ADR-037 es del módulo de Carlos: **[Falta: quién aprueba esta enmienda (Carlos o Manuel), fecha y medio.]**

**No autoriza código.** Este documento entra en el repositorio como propuesta; el cambio de permiso necesita la aprobación de esta enmienda y su propio plan (regla 3.4). Ningún fichero de código cambia con ella.

**Por qué es una enmienda.** Cambia quién puede leer una ruta existente (permisos y roles): regla 3.5 del encargo del 2026-11-15 («cambias permisos/roles») y checklist 3.3, punto 1.

**Enmienda a:** ADR-037 (Convocatoria, ledger, asignación y `DonationIntent`), con sus enmiendas 1 a 5. No toca ADR-044: DD-43 (quién lee la predicción) no cambia.

## Context

### Hechos verificados (`develop` `87a39ae`, 2026-11-15)

- `GET /api/v1/organizations/{organizationId}/campaigns` es solo de `ADMINISTRATOR` de la organización: `OrganizationCampaignsQuery.list` llama a `ConvocatoriaAuthorizationPolicy.requireAdministratorOf` (`referencia-api-v1.md`, fila del listado; DD-49).
- La predicción y su historial son de `ADMINISTRATOR` **o `REPRESENTATIVE`** (DD-43, ratificada). Para pedirlas hace falta el `campaignRef`.
- El `REPRESENTATIVE` no tiene de dónde sacar ese `campaignRef`. Contra la demo local, el 2026-11-15:
  - `GET /organizations/{id}/campaigns` → `403`;
  - `GET /me/campaigns` → `200 {"items":[]}` (solo trae convocatorias asignadas, y un representante no tiene);
  - `GET /organizations/{id}/campaigns/{campaignRef}/prediction` → `200` (sí puede leerla si conoce la referencia).
- Consecuencia en los fronts:
  - web, `/panel/prediction` y `/panel/configuration`: piden al representante **escribir la referencia interna** («Tu cuenta no puede ver el listado de convocatorias; escribe la referencia»);
  - móvil, `/prediction`: no ofrece escribirla (DDM-38) y, hasta la corrección del 2026-11-15, mostraba «Tu organización todavía no tiene convocatorias», que era falso.
- La solicitud S-04 del móvil (2026-10-07) ya pedía esto y quedó «parcial».
- `ConvocatoriaAuthorizationPolicy.requireApproverOf` ya expresa «`ADMINISTRATOR` o `REPRESENTATIVE` de la organización» (Enmienda 1 §3.2 y Enmienda 4, D2).

### Qué devuelve hoy el listado

`{items: [{campaignRef, publicCode, title, status, visibility, currency?, targetAmount?, targetPolicy?, clearedAmount?, responsibles: [{accountId, actingRole}], assignedEmployeeCount, startDate, endDate}]}`, una página de 100.

## Decision

### D1. Lectura del listado para `ADMINISTRATOR` o `REPRESENTATIVE`

`GET /api/v1/organizations/{organizationId}/campaigns` admite a un `ADMINISTRATOR` **o a un `REPRESENTATIVE`** de esa organización. Opción **(a)**: el representante recibe **la misma respuesta** que el administrador, sin vista reducida.

- Cualquier otro rol, otra organización o una organización inexistente: el mismo `403` de hoy (DD-01).
- La forma de la respuesta, el orden y el tope de 100 (DD-49) no cambian.

### D2. Ninguna escritura cambia

Crear convocatoria, editar la configuración, asignar empleados, designar o retirar responsables, cerrar y la portada siguen siendo **solo** de `ADMINISTRATOR` (`requireAdministratorOf`; ADR-051 C1 (a) para la portada). Esta enmienda no da al representante ninguna operación nueva de escritura.

### D3. Sin puerto ni dependencia nuevos

El cambio es de la comprobación de rol de una consulta. No cambia ningún puerto entre módulos ni el modelo de datos.

## Alternatives

| Opción | Por qué no |
|---|---|
| (b) Vista reducida para el representante (`campaignRef`, `publicCode`, `title`, `status`, fechas) | Dos formas de respuesta para una misma ruta. El representante ya lee la integridad (G-7), la actividad (G-8) y la predicción de su organización, y aprueba cambios de configuración; los importes y los responsables no le son ajenos |
| Predicción por `publicCode` | Cambia el contrato de la predicción (DD-43) y no resuelve `/panel/configuration` |
| Dejarlo como está y que escriba la referencia | La referencia es un identificador interno que el representante no ve en ninguna pantalla; el móvil ni siquiera lo permite (DDM-38) |
| Ampliar `GET /me/campaigns` | Esa ruta es «mis convocatorias asignadas» (§3.4, DD-72); mezclar en ella las de toda la organización cambia su significado para los empleados |

## Consequences

- El representante gana la lectura de `clearedAmount`, `targetAmount` y los `accountId` de los responsables de las convocatorias de **su** organización. Es una ampliación de lectura consciente (D1, opción (a)).
- Web: `/panel/prediction` y `/panel/configuration` muestran el selector de convocatorias también al representante; el campo de «escribe la referencia» queda como respaldo si el listado falla.
- Móvil: `/prediction` muestra el listado al representante sin cambios de código (ya lo pide primero).
- Documentos que cambian al implementarla: `referencia-api-v1.md` (nota fechada en la fila del listado), `api-contract-matrix.md` (§2b) y las decisiones DDM-38 y S-04 del móvil (quedan resueltas).

## Definición de hecho (tests, rojos primero) — para cuando se apruebe

1. `REPRESENTATIVE` de la organización → `200` con los mismos campos que el `ADMINISTRATOR`.
2. `EMPLOYEE` de la organización, representante de **otra** organización y cuenta sin organización → `403`, el mismo de hoy.
3. Aserciones negativas: con un `REPRESENTATIVE`, cada ruta de escritura de convocatoria (crear, configurar, asignar, designar, retirar, cerrar, portada) sigue respondiendo `403`.
4. `mvn test -pl convocatoria` y `-pl app` completos, y `scripts/ci-local.sh`, con la salida literal de Surefire.
