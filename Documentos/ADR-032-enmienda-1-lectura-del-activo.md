# ADR-032 — Enmienda 1: la lectura del lote para `ADMINISTRATOR` y `REPRESENTATIVE` de la misma organización

## Status

**PROPUESTA — espera la aprobación de Carlos** (ADR-032 es de `core` y lo aprobó él en la Fase 5). Redactada el 2026-11-19 por el agente, a petición de Manuel (encargo «firmas recibidas y siguiente tanda», plan 1, opción (b)). Su plan: `plan-tanda-2026-11-19.md`, Plan 1. **No autoriza código por sí sola:** el PR de código no se fusiona antes de que esta enmienda esté aprobada (regla 3.5).

## Contexto (verificado sobre `develop` `80af360`)

- `GET /api/v1/physical-assets/{assetRef}` (la ficha del lote) responde con los 7 campos de `PhysicalAssetOperationalReadModel` solo si quien la pide tiene `EMPLOYEE`: `PhysicalAssetOperationalQueryService.loadAuthorized`, `core/src/main/java/com/traceability/core/application/query/PhysicalAssetOperationalQueryService.java:105-107` (`InsufficientRoleException` → 403). La frontera de organización se comprueba antes (`:104`, `OrganizationBoundaryPolicy.assertBelongs`). Origen: DD-14 (RATIFICADA — Carlos, 2026-10-07), «lecturas con `EMPLOYEE` + frontera de organización (matriz §4b)»; `api-contract-matrix.md:98`.
- Consecuencia: un `ADMINISTRATOR` sin `EMPLOYEE` y un `REPRESENTATIVE` de la misma organización reciben 403 al abrir un lote de su propia organización. La web que se entregó con `PaxFide#72` muestra en esa ficha la sección «Pasos y fotos» y, **solo al `ADMINISTRATOR`**, el control de publicar la copia censurada (ADR-051 Enm. 2, E2-5 y E2-6): hoy solo lo puede usar una cuenta con los dos papeles.
- Los endpoints de fotos (`GET …/evidence/steps`, `…/evidence/{step}`, `…/evidence/redacted`) ya admiten a miembros con **cualquier rol**; la lista de lotes de la organización admite `ADMINISTRATOR` o `EMPLOYEE` (`PhysicalAssetOperationalQueryService.listForOrganization`, `:70-78`). La ficha es la única lectura del lote que se quedó con un solo rol.

## Decisión

- **E1-1.** `findOperationalView` (la lectura del lote) admite a quien tenga **`ADMINISTRATOR`, `REPRESENTATIVE` o `EMPLOYEE`** y pertenezca a la **misma organización** que el activo. La respuesta es la misma, **sin ningún campo nuevo**.
- **E1-2. Lo que no cambia.**
  - **Ningún comando.** Registrar, registrar desde donación, dividir, despachar, recibir y entregar siguen exigiendo `EMPLOYEE` (`RoleAuthorizationPolicy.java:21-22`).
  - `findSplitStatus` (`GET …/splits/{child}`) **sigue exigiendo `EMPLOYEE`**: solo el `EMPLOYEE` puede dividir.
  - La lista de lotes no cambia (`ADMINISTRATOR` o `EMPLOYEE`).
  - **La plataforma** (`platformAuthority`, sin organización) **sigue sin esta ruta**: usa sus rutas propias.
- **E1-3. Respuestas de error, sin cambio.** Otra organización, una cuenta sin organización y un activo inexistente reciben **el mismo 403** (DD-01 y DD-12, RATIFICADAS): sin oráculo de existencia. Sin JWT, 401.
- **E1-4.** Se actualizan, con nota fechada y sin reescribir lo anterior, DD-14 (`decisiones-delegadas-2026-10.md`) y la fila de `api-contract-matrix.md:98`.

## Alternativas

| Opción | Veredicto |
|---|---|
| (a) Una ficha reducida en la web para quien no puede leer el lote | Descartada por Manuel (2026-11-19): deja al `ADMINISTRATOR` con una pantalla distinta de la del `EMPLOYEE` y duplica lógica en la web |
| Abrir también `findSplitStatus` y la lista al `REPRESENTATIVE` | Más superficie sin necesidad: nadie más que el `EMPLOYEE` divide |
| Abrir la lectura también a la plataforma | Fuera de alcance: tiene rutas propias; se pediría aparte |

## Consecuencias

- Los 7 campos (`currentCustodianRef`, `currentLocation`, `zoneName`…) los verán dos roles más **de la misma organización**; hoy los ve cualquier `EMPLOYEE` de ella. No sale ningún dato a otra organización.
- Cambia un test de seguridad existente a propósito (`AssetHttpCommandsIntegrationTest.reads_requireTheEmployeeRoleAndTheOrganization`, `:266-275`, que hoy espera 403 para un `ADMINISTRATOR` sin `EMPLOYEE`).
- Riesgo: que abrir la lectura se tome por abrir también los comandos. Lo cubre un test `never()` por comando y rol (plan 1, test 4).

## Definición de hecho

Los tests 1 a 11 del Plan 1 de `plan-tanda-2026-11-19.md` en verde, con el rojo previo visible y las mutaciones muertas.
