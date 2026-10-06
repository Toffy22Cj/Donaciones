# Auditoría de Avance — Convocatoria Fase 6

## 1. Inventario de Trabajo y Estado

| ID | Área | Pertenece a Convocatoria | Estado | Evidencia | Bloquea Convocatoria | Peso |
|----|------|---------------------------|--------|-----------|----------------------|------|
| C-01 | Crear y configurar Convocatoria | SÍ | IMPLEMENTADO Y TESTEADO | `CreateConvocatoriaCommand`, `ConvocatoriaTest` | No | 1 |
| C-02 | Modelo de Campaña (visibilidad, estados) | SÍ | IMPLEMENTADO Y TESTEADO | `ConvocatoriaStatus`, `TargetPolicy` | No | 1 |
| C-03 | Asignación de Responsable | SÍ | IMPLEMENTADO Y TESTEADO | `ResponsibleAssignmentService`, tests | No | 1 |
| C-04 | DonationIntent (Creación y ciclo de vida) | SÍ | IMPLEMENTADO Y TESTEADO | `DonationIntentService`, `DonationIntentTest` | No | 1 |
| C-05 | Confirmación de Intención (Manual) | SÍ | IMPLEMENTADO Y TESTEADO | `confirmIfPending` en Repository, `DonationIntentService` | No | 1 |
| C-06 | CampaignFundingLedger (Incrementos) | SÍ | IMPLEMENTADO Y TESTEADO | `CampaignFundingLedgerService`, `CampaignFundingLedgerTest` | No | 1 |
| C-07 | Aplicación de fondos (Barrera/ApplyFunds) | SÍ | IMPLEMENTADO Y TESTEADO | `applyFundsForIntent`, barrera de comando `APPLY_FUNDS` | No | 1 |
| C-08 | Rechazo de Financiación (`FUNDING_REJECTED`) | SÍ | IMPLEMENTADO Y TESTEADO | `markFundingRejectedIfConfirmed`, tests | No | 1 |
| C-09 | Idempotencia y Concurrencia (WriteConflict) | SÍ | IMPLEMENTADO Y TESTEADO | `IdempotentCommandExecutor`, `ConvocatoriaTransactionRetryHelper` | No | 1 |
| C-10 | Recuperación: Consulta de intenciones | SÍ | IMPLEMENTADO Y TESTEADO | `findConfirmedPendingApplication` | No | 1 |
| C-11 | Recuperación: Reintroducción de `CLOSE_ON_TARGET` (R4) | SÍ | DIFERIDO EXPLÍCITAMENTE | `estado-fase6.md` §5 | No | 1 |
| C-12 | Registro de dinero rechazado (P1) | SÍ | DIFERIDO EXPLÍCITAMENTE | `estado-fase6.md` | No | 1 |
| E-01 | Adaptador de `OrganizationVerificationPort` (ADR-038) | NO | DEPENDENCIA EXTERNA | Faltante en `app`/`identity` | Sí (Golden Path) | 1 |
| E-02 | Orquestador transaccional (Ledger+Genesis+Outbox) | NO | DEPENDENCIA EXTERNA | Faltante en `app` | Sí (Golden Path) | 1 |
| E-03 | T1: `clearFundsGenesis` sin reintento interno | NO | DEPENDENCIA EXTERNA | `core` `FundCommandService` | Sí (Golden Path) | 1 |
| E-04 | P8: Mensaje de Outbox de la génesis | NO | DEPENDENCIA EXTERNA | Faltante en `core` | Sí (Golden Path) | 1 |
| E-05 | Scheduler de Recuperación | NO | DEPENDENCIA EXTERNA | Faltante en `app` | Sí (Golden Path) | 1 |
| E-06 | Endpoints API para Convocatoria | NO | DEPENDENCIA EXTERNA | Faltante en `api` | Sí (Golden Path) | 1 |
| E-07 | Webhook / Pasarela de Pago | NO | DEPENDENCIA EXTERNA | Faltante en `app`/`api` | Sí (Golden Path) | 1 |
| E-08 | Frontend de Convocatoria | NO | DEPENDENCIA EXTERNA | Faltante en UI | Sí (Golden Path) | 1 |
| E-09 | Identidad: VERIFY repetido, comportamientos pendientes | NO | DEPENDENCIA EXTERNA | `identity` | Sí (Golden Path) | 1 |

*Explicación de pesos:* Cada capacidad representa un flujo funcional completo, verificable mediante tests o integraciones e2e independientes, por lo tanto, todas tienen el mismo peso (1 unidad).

## 2. Cálculo de Porcentajes

### A. % DEL BLOQUE CONVOCATORIA (PROPIA)

- **Total de unidades en alcance propio (C-01 a C-12):** 12
- **Implementado y verificado (C-01 a C-10):** 10
- **Diferidos explícitamente (C-11, C-12):** 2

**Fórmula:**
`10 (Implementados) / 12 (Total Convocatoria) = 83.33%`

Si consideramos las tareas "Diferidas explícitamente" como tareas fuera del corte actual porque su diseño posterior es una deuda aceptada y documentada, el porcentaje operativo implementado para el bloque asciende al **100%** de lo exigible hoy. Sin embargo, usando la matemática estricta sobre el 100% de los elementos identificados: **83.33%**.

### B. % DE CONVOCATORIA INTEGRADA EN EL PRODUCTO COMPLETO

Esta métrica evalúa el E2E del Golden Path, considerando todo el ecosistema (10 unidades propias completadas + 9 unidades externas necesarias para integración).

- **Unidades operativas E2E:** 0 (Nada del trabajo de Convocatoria es operable de inicio a fin desde una API, frontend o webhook ya que falta el ensamble con dependencias).
- **Unidades E2E requeridas para el Golden Path (C01-10 + E01-09):** 19

**Fórmula:**
`0 (Operativas E2E) / 19 (Total requeridas E2E) = 0%`

*Aunque el núcleo de Convocatoria funciona perfectamente en tests de unidad y de integración con BD local, el grado de integración real sistémica (con la capa HTTP, el orquestador principal, y los puertos externos de core/identidad) es 0%.*

## 3. Tabla Resumen

| Área | Estado | Convocatoria propia | Integración global | Responsable |
|------|--------|---------------------|--------------------|-------------|
| DonationIntent | IMPLEMENTADO Y TESTEADO | 100% | 0% | Convocatoria |
| Campaign | IMPLEMENTADO Y TESTEADO | 100% | 0% | Convocatoria |
| Assignment | IMPLEMENTADO Y TESTEADO | 100% | 0% | Convocatoria |
| Ledger | IMPLEMENTADO Y TESTEADO | 100% | 0% | Convocatoria |
| Confirmación | IMPLEMENTADO Y TESTEADO | 100% | 0% | Convocatoria |
| Aplicación fondos | IMPLEMENTADO Y TESTEADO | 100% | 0% | Convocatoria + App/Core |
| Fund | DEPENDENCIA EXTERNA | N/A | 0% | Core/App |
| Identidad/autorización | DEPENDENCIA EXTERNA | N/A | 0% | Identity |
| Webhook/pasarela | DEPENDENCIA EXTERNA | N/A | 0% | APIs / App |
| API | DEPENDENCIA EXTERNA | N/A | 0% | APIs |
| Frontend | DEPENDENCIA EXTERNA | N/A | 0% | Frontend |
| Recuperación | IMPLEMENTADO Y TESTEADO | 100% | 0% | Convocatoria / App |
| Golden Path | DEPENDENCIA EXTERNA | N/A | 0% | Integración |

## 4. Las Tres Listas

### A. HECHO Y CERRADO
- Creación, edición y cierre de Convocatorias.
- Asignación de responsables.
- Creación y gestión de estado de DonationIntent.
- Confirmación manual de intenciones (aislando la pasarela).
- Aplicación de fondos (creación y reclamo idempotente de la barrera `APPLY_FUNDS`).
- Incrementos condicionales del `CampaignFundingLedger`.
- Idempotencia transaccional y retry de conflictos (`WriteConflict`) contra MongoDB.
- Rechazo de fondos (`FUNDING_REJECTED`) manejado para errores permanentes.
- Consulta de recuperación para fondos confirmados pendientes de aplicar.

### B. PENDIENTE DENTRO DE CONVOCATORIA
- R4: Gestión de reintroducción de intenciones con `CLOSE_ON_TARGET + CLOSE`. (Diferido)
- P1: Registro del dinero no aceptable. (Diferido)
- P10: Trazabilidad en AuditLog del estado `FUNDING_REJECTED`. (Diferido)

### C. PENDIENTE POR DEPENDENCIAS EXTERNAS (BLOQUEANTES PARA INTEGRACIÓN)
- Orquestador transaccional en `app` (Acto 2 que une Convocatoria, Ledger, Genesis y Outbox).
- T1: Camino sin reintento interno de `clearFundsGenesis` en `core`.
- Adaptador real `OrganizationVerificationPort` en `identity`/`app` (ADR-038).
- Scheduler en `app` para invocar la recuperación regularmente.
- Endpoints en `api` para operar todo este dominio expuesto.
- P8: Mensaje de Outbox de la génesis en `core`.
- Integración real de Pasarela/Webhook (P3) en `app` / `api`.

## 5. Cuánto Falta

- **Qué falta para cerrar MI BLOQUE:** El código estricto de Convocatoria Fase 6 ya está cerrado para este corte activo (las 10 piezas están testeadas rigurosamente). Sólo faltan ítems documentados como diferidos explícitamente (R4, P1, P10) que no exigen ser abordados ahora.
- **Qué falta después de que mis compañeros terminen:** Ensamblar el orquestador transaccional que instanciará las operaciones de Convocatoria y Core juntas de forma atómica.
- **Qué falta para que Convocatoria funcione E2E:** Terminar el Orquestador en `app`, cerrar la API REST, habilitar los flujos en Frontend y cerrar T1 en `core`.
- **Qué cosas están deliberadamente fuera de alcance:** P2, P4, P5, P6, P7 y cualquier soporte de efectivo o transacciones/confirmaciones fuera de la especificación de este hito.
- **Qué cosas están documentadas como deuda futura:** R4 (cómo tratar las intenciones `CLOSE` recuperables), P1 (registro de dinero rechazado de pasarela) y P10 (Trazabilidad estricta y fecha de `FUNDING_REJECTED`).

## 6. Dictamen

### BLOQUE CONVOCATORIA
**CERRADO** (El 100% de lo operable de este corte interno ya está en código y testeado).

### INTEGRACIÓN CONVOCATORIA
**DEPENDENCIAS EXTERNAS**

## 7. Evidencia y Pruebas Ejecutadas

**Comandos ejecutados:**
`mvn test -pl convocatoria`

**Resultado:**
- Tests run: 194, Failures: 0, Errors: 0, Skipped: 0
- Log muestra retries automáticos correctos contra MongoDB real (TransientTransactionError / WriteConflict) e Inserciones exitosas.

**Hallazgos estructurales:**
- **HECHO OBSERVADO:** `convocatoria/src/main/` contiene el domino completo: entidades, servicios y puertos out de la lógica. (e.g. `DonationIntentService`, `applyFundsForIntent`, `findConfirmedPendingApplication`).
- **HECHO OBSERVADO:** No hay importación ni uso de `convocatoria` desde `app`, `api` ni `core`. `app/pom.xml` no inyecta `convocatoria`. Por ende la integración e2e es 0%.
- **DEPENDENCIA EXTERNA:** `FundCommandService` en `core` reintenta en su interior (verificado en documentación previa y manual), incumpliendo T1 (reflejado en los documentos como deuda técnica externa).
- **INFERENCIA:** Al no existir orquestador principal en `app` que inyecte dependencias cruzadas (Convocatoria + Core), la aplicación de fondos no puede despachar transacciones atómicas de lado a lado.
