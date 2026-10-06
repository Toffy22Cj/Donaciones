# Auditoría de herencia Fase 5 → Convocatoria (Fase 6)

**Tipo:** solo lectura. No se modificó código, tests ni documentación. No se ejecutaron tests en esta auditoría.
**Fecha:** 2026-10-03. **Rama:** `develop` (HEAD `673eda9`; `convocatoria/` sin versionar).
**Perímetro:** solo el bloque Convocatoria de Fase 6. Otros bloques aparecen únicamente cuando una dependencia concreta nace en Fase 5.

Categorías: HEREDADO · EVOLUCIONADO · SUSTITUIDO · CONTRADICCIÓN · DEPENDENCIA · HISTÓRICO · FUERA DE PERÍMETRO.

---

## 1. Resumen ejecutivo

- **El código de `convocatoria` hereda poco de Fase 5 y lo hace solo a través de `contracts`.** La única dependencia Maven interna es `contracts` (`convocatoria/pom.xml:19`). Los únicos tipos importados son `IdentityPrincipalPort`, `AuthorizationPrincipal` y `AuthorizationRole`, que nacieron en Fase 5 (`8012f87`, ADR-032/D1). `ConvocatoriaArchitectureTest` prohíbe depender de `core`, `identity` y `app` (líneas 20-24).
- **La herencia fuerte está en `core`, al otro lado de la frontera.** Afecta al Acto 2 (génesis del `Fund`): el invariante de `organizationRef` (ADR-028), la matriz de ADR-032, el bypass de `SystemActor` y `ExternalActor` (ADR-031/035) y el patrón `processed_commands`/`tryClaim`. Esa herencia condiciona el diseño de Convocatoria, pero se materializa en `app`/`core`, no en el módulo. Por eso los pendientes T1, P8 y el espacio de claves de la génesis son **DEPENDENCIAS** ya declaradas (Enmienda 2 §6).
- **El código de Convocatoria no contiene ninguna contradicción con Fase 5.** Solo cita ADR-037, 038, 041 y 043 (87/5/2/4 apariciones), ningún número del rango en colisión 033-036.
- **Hay tres contradicciones documentales heredadas** (§7):
  - **H-1 (requiere decisión humana):** la renumeración de los ADR de Fase 6 figura como "aprobada el 2026-09-28" en los documentos de Convocatoria. La fuente que citan no existe en ninguna rama, y los documentos de Fase 5 la siguen dando por "PENDIENTE DE DECISIÓN HUMANA — sin renumerar".
  - **H-2:** los documentos de Convocatoria citan un "respaldo de `REPRESENTATIVE` acotado por ADR-032" que ADR-032 no contiene.
  - **H-3:** varias notas sobre el nombre del archivo y el número "tentativo" están desactualizadas.
- **Dictamen: REQUIERE DECISIÓN HUMANA**, limitado a ratificar el catálogo de numeración (H-1). No afecta al código ni a las decisiones F-1, F-2, D1-D7 o `FUNDING_REJECTED`.

## 2. Mapa Fase 5 → Fase 6 (Convocatoria)

| Elemento de Fase 5 | Dónde lo encuentra Convocatoria | Categoría | Evidencia |
|---|---|---|---|
| Puerto `IdentityPrincipalPort` + `AuthorizationPrincipal` + `AuthorizationRole` (ADR-032/D1) | `ConvocatoriaAuthorizationPolicy`, `ConvocatoriaActor` | HEREDADO | `contracts/.../authorization/*` (`8012f87`); `ConvocatoriaAuthorizationPolicy.java:44,59` |
| Matriz de roles de ADR-032 (`CLEAR_FUNDS_AS_GENESIS → ADMINISTRATOR`) | Confirmación manual (D5) | EVOLUCIONADO | ADR-032:47; `RoleAuthorizationPolicy.java:20`. La Enmienda 2 §3.1 (línea 71) mantiene la exigencia de `ADMINISTRATOR`, pero la aplica a la confirmación, que ya no es la génesis |
| `REPRESENTATIVE` excluido de los comandos operativos (ADR-032:53) | `REPRESENTATIVE` no aplica a Convocatoria | HEREDADO | `ConvocatoriaAuthorizationPolicy.java:17`; `RoleAuthorizationPolicyTest.java:66` |
| Respaldo de `REPRESENTATIVE` ("enmienda ADR-032") | P6 | CONTRADICCIÓN (H-2) + DEPENDENCIA | ADR-032 no lo contiene; Enmienda 1 §7.1 y P6 |
| `ActorRef` sellado (ADR-030/031) + `HumanActor` (ADR-035) | Convocatoria no usa `ActorRef`; tiene `ConvocatoriaActor` propio | SUSTITUIDO (dentro del módulo) | `ConvocatoriaActor.java:9`, `ConvocatoriaAuditEntry.java:9` ("nunca `core.domain.event.ActorRef`", ADR-037 §6) |
| Bypass de `SystemActor`/`ExternalActor` en `FundCommandService.authorize` | La génesis disparada por el sistema (D6) no pasa por la matriz | DEPENDENCIA | `FundCommandService.java:51-65` |
| `organizationRef` obligatorio en la génesis (ADR-028) | El Acto 2 debe aportar `organizationRef` desde la intención | HEREDADO (en `core`) | `Fund.java:94-97` (`InvalidFundGenesisException`) |
| `campaignRef`/`donorRef` en `FundsClearedV2Payload` | El Acto 2 pasa `campaignRef` a la génesis | HEREDADO (en `core`) | `FundCommandService.java:83`; `Fund.java:103` |
| `processed_commands` + `tryClaim(String)` + retorno silencioso | Reimplementado en `convocatoria` con tipo y resultado | EVOLUCIONADO | `core/.../MongoProcessedCommandAdapter.java:30`; `TransactionalEventPublisher.java:27-31`; `FundCommandService.java:84-86`; Enmienda 1 §3.5; Enmienda 2 §3.3 |
| `TransactionalEventPublisher.appendAndOutbox` (`@Transactional` REQUIRED) | Se une a la transacción única del orquestador | HEREDADO | `TransactionalEventPublisher.java:26` |
| `CommandRetryTemplate` alrededor de la génesis | Prohibido reintentar dentro de la transacción del orquestador | DEPENDENCIA (T1) | `FundCommandService.java:88`; Enmienda 2 línea 79, tabla línea 122 |
| Génesis sin mensaje de outbox (`List.of()`) | ADR-037 §2.3 exige el outbox | DEPENDENCIA (P8) | `FundCommandService.java:94`; Enmienda 2 línea 81 |
| Derivación de `organizationRef` para `ExternalActor` ("pendiente desde Fase 5") | Resuelto para Convocatoria vía `DonationIntent` (ADR-037 §2.6) | EVOLUCIONADO | `contract-wiring-review.md:53`; ADR-037:104 |
| ADR-029 (`PhysicalAsset`, donación en especie) | `campaignRef` en activos | DEPENDENCIA (fuera del cierre) | Enmienda 1 §7.2 y líneas 280-282 |
| ADR-033 (saga de registro), ADR-034 (pending allocation), ADR-036 (reversión administrativa) | Sin contacto con Convocatoria | FUERA DE PERÍMETRO | `estado-fase5.md:24-27` |
| ADR-042 (reintentos de proyección) + `hallazgo-framework-retry-projections.md` | Solo como contraste: no cubre la recuperación de fondos | FUERA DE PERÍMETRO | ADR-043:17 |
| ADR-038/039 locales de Fase 5 (`b4f04cb`, `ad130d7`, `5c480c5`) | Commits huérfanos (no están en ninguna rama). Los números 038/039 los ocupan hoy Identidad y Blockchain de Fase 6 | HISTÓRICO | `git branch -a --contains` vacío; `estado-fase5.md:34-35` |
| Patrón de sondeo seguro `BlockchainAnchorProducer` (app) | Precedente del scheduler de ADR-043 | DEPENDENCIA de diseño (no es Fase 5) | ADR-043:15 |

## 3. ADRs: números, títulos y estado verificados

Catálogo físico en `Documentos/` (un archivo por número, salvo las enmiendas):

| Nº | Título (archivo) | Fase | Estado en cabecera | Relación con Convocatoria |
|---|---|---|---|---|
| 028 | Relación `Organization ↔ Fund` | 5 | Reconstrucción histórica (Aprobada en Fase 5) | HEREDADO (en `core`) |
| 029 | `Organization ↔ PhysicalAsset`, donación en especie | 5 | Reconstrucción histórica (revisado en C5) | DEPENDENCIA (fuera del cierre) |
| 030 | Ubicación y persistencia de `actorRef` | 5 | Reconstrucción histórica | SUSTITUIDO dentro del módulo |
| 031 | Taxonomía de `ActorRef` | 5 | Reconstrucción histórica | DEPENDENCIA (bypass del sistema) |
| 032 | Autorización de comandos de `core`, matriz | 5 | Reconstrucción histórica | EVOLUCIONADO / H-2 |
| 033 | Contrato de la saga de registro de activos | 5 | Aprobado parcialmente | FUERA DE PERÍMETRO |
| 034 | Read model de pending allocation | 5 | Approved | FUERA DE PERÍMETRO |
| 035 | Autorización de `HumanActor` | 5 | Approved | DEPENDENCIA indirecta |
| 036 | Reversión administrativa de asignación | 5 | Approved | FUERA DE PERÍMETRO |
| 037 | Convocatoria, ledger, assignment, DonationIntent (+ Enmiendas 1 y 2) | 6 | "número tentativo" (línea 1); la Enmienda 2 está en BORRADOR | Bloque auditado |
| 038 | Identidad: platform administrator, verificación de `Organization` | 6 | — | DEPENDENCIA (GAP-1, no es Fase 5) |
| 041 | APIs y frontend, contratos HTTP | 6 | "número tentativo" (línea 1) | DEPENDENCIA (no es Fase 5) |
| 042 | Orquestación centralizada de reintentos de proyección | 5 (A7.2) | Approved | FUERA DE PERÍMETRO |
| 043 | Recuperación de la aplicación de fondos | 6 | Propuesto | Bloque auditado |

Verificaciones de numeración:
- **No hay duplicados físicos hoy.** 033-036 son los ADR de Fase 5, 037-041 los de Fase 6, 042 es de Fase 5 y 043 de Fase 6. La colisión 033-036 descrita en `plan-correccion-fase5-e-ia.md:11` ya no existe como archivos duplicados: el ADR de Convocatoria se renombró a 037 en `b417d3a`.
- **Equivalencia histórica documentada:** "ADR-033 de Convocatoria" = ADR-037 (Enmienda 1:5-6; `convocatoria-resumen.md:99`). Es la única equivalencia que esta auditoría acepta, porque está escrita. Las demás (034→038, 035→039, 036→040, 037→041) aparecen en la misma nota de `convocatoria-resumen.md:99`, pero su fuente citada no existe (H-1).
- **La fuente de la renumeración no existe.** `ADR-042-frontend-web-paxfide-web.md` no está en el árbol ni en ninguna rama: `git log --all` sobre esa ruta no devuelve nada. El número 042 lo ocupa el ADR de reintentos de proyección.

## 4. Código heredado

| Pieza | Módulo | Categoría | Observación |
|---|---|---|---|
| `IdentityPrincipalPort`, `AuthorizationPrincipal`, `AuthorizationRole` | `contracts` | HEREDADO | Uso de solo lectura. `AuthorizationRole` = `{ADMINISTRATOR, REPRESENTATIVE, EMPLOYEE}` |
| `ConvocatoriaActor`, `ConvocatoriaAuditEntry` | `convocatoria` | SUSTITUIDO | Sustituyen a `ActorRef` dentro del módulo, por diseño (ADR-037 §6) |
| `MongoProcessedCommandAdapter` + `ProcessedCommandPort` + `CommandType.isSystem()` | `convocatoria` | EVOLUCIONADO | Mismo patrón que en `core` (claim atómico), con tres diferencias: comando con tipo, resultado guardado y separación de claves de sistema (D1) |
| `ConvocatoriaTransactionRetryHelper` | `convocatoria` | EVOLUCIONADO | Política de reintento propia (6 intentos, backoff exponencial). No hereda `CommandRetryTemplate` de `core` |
| `FundCommandService.clearFundsGenesis` | `core` | DEPENDENCIA | Comprueba `exists` antes de empezar, reintenta internamente (T1), no tiene outbox (P8), su `commandId` no tiene tipo (espacio de claves de la génesis) y aplica el bypass del sistema |
| `Fund.clearFundsGenesis` | `core` | HEREDADO | Invariantes de ADR-028: `organizationRef` no nulo y `amount > 0` |
| `TransactionalEventPublisher.appendAndOutbox` | `core` | HEREDADO | `@Transactional` REQUIRED; `tryClaim` sin tipo |
| `RoleAuthorizationPolicy` | `core` | HEREDADO | No se ejecuta en el camino de sistema (D6) |

## 5. Tests heredados

- **`RoleAuthorizationPolicyTest`** (`core`, Fase 5): la línea 66 verifica que `REPRESENTATIVE` se rechaza. Es coherente con ADR-032:53 y con `ConvocatoriaAuthorizationPolicy`. HEREDADO.
- **Fase 5 no dejó tests de duplicado ni concurrencia de `clearFundsGenesis`** dentro de una transacción externa. La cobertura de la barrera de Convocatoria vive solo en `CampaignFundingLedgerIntegrationTest`, con 31 tests (duplicado, concurrencia, separación de claves y transacción externa). El hueco del lado de `core` forma parte de T1 y del espacio de claves de la génesis. DEPENDENCIA.
- **`ConvocatoriaArchitectureTest`** (Fase 6) es la red que impide heredar `core`, `identity` o `app` por accidente. No es herencia: es la garantía de la frontera.
- **No se ejecutaron tests en esta auditoría.** El último resultado registrado de la sesión anterior es `mvn -o test -pl convocatoria` → 194/0/0/0. No se vuelve a afirmar aquí como evidencia nueva.

## 6. Contratos

| Contrato | Origen | Categoría | Estado para Convocatoria |
|---|---|---|---|
| `IdentityPrincipalPort.resolvePrincipal(accountId)` | Fase 5 (ADR-032/D1) | HEREDADO | Lo usan la confirmación manual (D5) y la gestión de responsables |
| Firma de `clearFundsGenesis(commandId, fundId, organizationRef, campaignRef, donorRef, currency, amount, sourceRef, actorRef)` | Fase 5 | DEPENDENCIA | La consumirá el orquestador de `app`, no `convocatoria` |
| Evento `FUNDS_CLEARED` v2 con `campaignRef`/`donorRef` | `core`, anterior a Fase 6 | HEREDADO | Sin cambios requeridos por Convocatoria |
| `contract-wiring-review.md` P1/P2/P3 | Revisión de cableado | EVOLUCIONADO | P2 está cerrado. P3 sigue abierto en lo relativo al webhook y al proveedor (Enmienda 1:209). D4 deja P1 fuera del corte |

## 7. Contradicciones

### H-1 — Estado de la renumeración de los ADR de Fase 6 · REQUIERE DECISIÓN HUMANA

- `convocatoria-resumen.md:99` y Enmienda 1:6 dicen que la renumeración quedó "aprobada el 2026-09-28 por decisión humana explícita (tabla en `ADR-042-frontend-web-paxfide-web.md`)".
- Ese archivo no existe en ninguna rama, y 042 es el ADR de reintentos de proyección de Fase 5.
- `plan-correccion-fase5-e-ia.md:28-32` y `estado-fase5.md:33` siguen diciendo "PENDIENTE DE DECISIÓN HUMANA — sin renumerar". También dicen que 037 corresponde a APIs/Frontend, cuando hoy es Convocatoria y APIs/Frontend es 041.
- Las cabeceras de ADR-037 y ADR-041 conservan "número tentativo — confirmar contra el catálogo real antes de commitear".
- **Impacto:** el catálogo físico es coherente, pero la decisión que lo legitima no tiene registro verificable. Según `convocatoria-resumen.md:91`, una decisión humana no es normativa hasta que se registra. Aprobar la Enmienda 2 y ADR-043, que citan "ADR-037", se apoya sobre esa numeración. No afecta al código: el código no cita 033-036.
- **Pregunta para el humano:** ¿se ratifica el catálogo actual (037 Convocatoria … 041 APIs, 042 reintentos de proyección, 043 recuperación de fondos) y dónde queda registrada esa decisión?

### H-2 — "Respaldo de `REPRESENTATIVE` acotado por ADR-032" · CONTRADICCIÓN documental, no bloqueante

- `convocatoria-resumen.md:97`, ADR-037:158 y Enmienda 1:307 hablan del respaldo de `REPRESENTATIVE` como algo definido por ADR-032.
- ADR-032 no lo define. Su línea 53 dice lo contrario: `REPRESENTATIVE` "fue deliberadamente excluido de los comandos operativos directos iniciales". El código tampoco lo tiene (no hay ninguna regla para `REPRESENTATIVE` en `core/src/main`).
- El origen es una "enmienda ADR-032" prevista y nunca escrita (`contract-wiring-review.md:42`; `identity-resumen.md:25`).
- **Impacto:** nulo para el cierre de Convocatoria. ADR-037:158 declara que ese respaldo no aplica a sus comandos, y P6 ya está registrado como pendiente. El texto, sin embargo, presenta como existente una regla que no existe.

### H-3 — Notas de estado desactualizadas · HISTÓRICO, no bloqueante

- Enmienda 1:7 y `convocatoria-resumen.md:99` dicen que el archivo "aún se llama `ADR-033-convocatoria-…`" y que el renombrado está pendiente. El archivo es `ADR-037-…` desde `b417d3a`.
- `estado-fase5.md:35` reserva el número 039 como "candidato para Fase 6". Hoy está ocupado por Blockchain, y el ADR-039 de A7.1 de Fase 5 sigue sin número publicado. Esto último está FUERA DE PERÍMETRO.

**No hay contradicciones entre Fase 5 y las decisiones cerradas** (F-1, F-2, `FUNDING_REJECTED`, D1-D7, reintento). En concreto:
- D5 (solo `ADMINISTRATOR`) es coherente con ADR-032:47.
- D6 (dispara el sistema) es coherente con el bypass de `SystemActor`.
- D1 (claves con tipo) no altera el `tryClaim` de `core`.

## 8. Dependencias abiertas que nacen en Fase 5

| ID | Origen en Fase 5 | Qué exige a Convocatoria/app | Registrada en |
|---|---|---|---|
| T1 | `CommandRetryTemplate` dentro de `clearFundsGenesis` | Génesis sin reintento interno dentro de la transacción del Acto 2 | Enmienda 2 líneas 79 y 122 |
| P8 | Génesis con `outboxMessages = List.of()` | Mensaje de outbox exigido por ADR-037 §2.3 | Enmienda 2 líneas 81 y 135 |
| Espacio de claves de la génesis | `tryClaim(String)` sin tipo; `exists` + retorno silencioso | `commandId` de la génesis sin colisión con claves de cliente | Sesión anterior (GAP-2); Enmienda 2 §6 |
| P6 | ADR-032 (respaldo nunca escrito) | Activación del respaldo con un `ADMINISTRATOR` responsable | Enmienda 1 §7.1 y §8 |
| P3 | Derivación de `organizationRef` para `ExternalActor` "pendiente desde Fase 5" | Webhook y proveedor de pago | Enmienda 1:209; Enmienda 2 línea 124 |
| ADR-029 / `campaignRef` en `PhysicalAsset` | Esquema de eventos de `PhysicalAsset` | Nueva versión de evento y upcaster | Enmienda 1 §7.2 (fuera del cierre) |

GAP-1 (adaptador de producción de `OrganizationVerificationPort`, ADR-038) bloquea `app`, pero **no nace en Fase 5**. Se menciona solo para que no se atribuya a Fase 5.

## 9. Lo que NO hay que tocar a raíz de esta auditoría

- `contracts/authorization/*`: contrato estable de Fase 5 que consumen `core`, `identity` y `convocatoria`.
- `RoleAuthorizationPolicy` y la matriz de ADR-032: D5 se apoya en ella tal como está.
- `FundCommandService.authorize`: el bypass de `SystemActor` es la base de D6.
- `Fund.clearFundsGenesis` y sus invariantes de ADR-028.
- ADR-033, 034, 036 y 042 y su código: fuera de perímetro.
- Las decisiones cerradas F-1, F-2, `FUNDING_REJECTED`, D1-D7 y la clasificación de reintentos: esta auditoría no aporta motivo para reabrirlas.
- Los ADR de Fase 5 no deben renumerarse: H-1 trata del registro de la decisión, no de mover archivos.

## 10. Dictamen

**REQUIERE DECISIÓN HUMANA**, de alcance estrictamente documental:
- **H-1:** ratificar y registrar el catálogo de numeración vigente, porque la aprobación citada no tiene fuente.
- Una vez resuelto H-1, el dictamen pasa a **LIMPIO CON HERENCIA**:
  - la herencia de Fase 5 en Convocatoria es coherente con las decisiones cerradas;
  - las dependencias que nacen en Fase 5 (T1, P8, espacio de claves de la génesis, P6, P3) ya están registradas como pendientes de `core`/`app`;
  - H-2 y H-3 son correcciones de redacción que no bloquean nada.

No se detectó ninguna herencia de Fase 5 que contradiga el código de `convocatoria`.
