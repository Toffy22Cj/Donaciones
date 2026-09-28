# Estado — Fase 5: CERRADA

**Proyecto:** Motor de Trazabilidad Verificable de Donaciones (`com.traceability`)
**Fase actual:** Fase 5 — Cierre histórico previo + remediación técnica del Bloque A **completada** en la rama `feat/fase5-a7-1-a7-2` (C1–C6, ver §12.8). Trabajo posterior: Fase 6.
- **Bloque de dominio** (`Organization↔Fund`, `Organization↔PhysicalAsset`, donación en especie, `actorRef`) — **DISEÑO Y IMPLEMENTACIÓN CERRADOS**
- **Bloque C/D** (autorización, puerto Identity↔Core) — **DISEÑO E IMPLEMENTACIÓN CERRADOS (ADR-032, ADR-035).**
- **Bloque A (remediación post-auditoría)** — **CERRADO** con deudas explícitas y decisiones humanas pendientes listadas en §12.8.
Este documento registra el cierre histórico de la Fase 5, su posterior auditoría y la remediación técnica del Bloque A. Las secciones §3–§9 conservan el registro de diseño original; donde el estado posterior difiere se indica con una nota *(estado posterior)*.
**Fases previas:** 1, 2, 3 y 4 formalmente cerradas (ver `documento-maestro-proyecto.md`, `estado-fase4.md`).
**Alcance del bloque de dominio, fijado desde el primer intercambio de esta fase:** dominio de negocio únicamente. Autorización, autenticación y escritura HTTP quedaron explícitamente pospuestas a un bloque separado (Bloque C/D) desde antes de abrir la primera pregunta de arquitectura — esa frontera se mantuvo sin excepción durante todo el bloque de dominio, y Bloque C/D, ya abierto, respeta la misma disciplina de no inventar política de negocio sin evidencia documental (ver §9).

---

## 1. ADRs de esta fase

| ADR | Título | Status |
|---|---|---|
| ADR-028 | Relación `Organization ↔ Fund` | **Approved** |
| ADR-016 | Génesis Dual de `Fund` | **Approved with amendment** — `ClearFunds` se expresa en dos casos de uso de aplicación (`ClearFundsAsGenesis` / `ClearFundsForPledge`); `FUNDS_CLEARED:v2` mantiene un único esquema de payload en ambos |
| ADR-029 | `Organization ↔ PhysicalAsset` + Donación en Especie | **Approved** — Camino A y Camino B implementados. Revisado en C5 (`d09cc02`): el invariante `asset.organizationRef == Fund.organizationRef` del Camino A se hace cumplir en `registerPhysicalAsset` contra el `Fund` cargado (C3, `014d101`) |
| ADR-030 | `actorRef` — ubicación y persistencia | **Approved** |
| ADR-031 | Taxonomía de `ActorRef` | **Approved** |
| ADR-032 | Autorización de comandos en `core`: puerto Identity↔Core, guardas de pertenencia y rol, matriz de autorización | **Approved** |
| ADR-033 | Contrato del payload de la saga `ASSET_REGISTRATION_SAGA` | **Aprobado parcialmente** — actualizado en C5: productor real existente (A3) y envelope emitido documentado |
| ADR-034 | Visibilidad y Operabilidad de Pending Allocation (NUEVA-4 redefinida) | **Approved** |
| ADR-035 | HumanActor como variante de ActorRef y puente de autorización humana | **Approved** |
| ADR-036 | Reversión Administrativa de Asignación (NUEVA-4B) | **Approved** |
| ADR-042 | Orquestación centralizada de reintentos de proyección (A7.2) | **Approved** — creado en C5; refina ADR-010/ADR-017 |

*Nota documental post-auditoría*: Los archivos físicos de los ADR-028 a ADR-032 han sido incorporados en `Documentos/` mediante reconstrucción histórica rigurosa.

**Estado del catálogo tras C5** (verificado contra `feat/fase5-a7-1-a7-2`, `develop` local y `origin/develop`):
- **Colisiones 033–037 (Fase 5 ↔ Fase 6): PENDIENTES de decisión humana.** ADR-033 a ADR-036 existen dos veces (Fase 5: saga de registro, pending allocation, HumanActor, reversión administrativa; Fase 6: Convocatoria, Identidad, Blockchain, IA) y ADR-037 (APIs/Frontend, Fase 6) está marcado como "número tentativo". En este documento los números 033–036 se refieren siempre a los ADR de Fase 5. No se ha renumerado nada.
- **ADR-038** (`develop` local, no publicado, `b4f04cb`/`ad130d7`): **SUPERSEDIDO**. Su decisión (añadir `fundId` a `registerPhysicalAsset`) se implementó de otra forma en A3/C3 y su §4 dependía de `RedundantDomainActionException`, eliminada por A7.1. El envelope vigente quedó documentado en ADR-033. No se porta.
- **ADR-039** (`develop` local, no publicado, `5c480c5`): **PENDIENTE DE RESOLVER NUMÉRICAMENTE**. Su contenido (no-op idempotente para redundancia exacta) coincide en sustancia con la implementación vigente de A7.1 (`e10a3ca`), pero cita como evidencia la variante no canónica `04ad840` y el número 039 está reservado como candidato para Fase 6 en `plan-correccion-fase5-e-ia.md`. A7.1 no tiene todavía un ADR publicado.
- **ADR-042**: primer número libre en todas las ramas y no reservado; usado para A7.2.
- La reconstrucción alternativa de ADR-028 a ADR-032 presente en `develop` local (`cd56d22`, otros nombres de archivo) es material histórico descartado: los originales existen en `Documentos/`.

---

## 2. Decisiones congeladas — resumen ejecutivo

### `Organization ↔ Fund` (ADR-028)
- `Organization` **gestiona/opera** el `Fund` — autoridad operativa, explícitamente no ownership jurídico ni financiero.
- `Fund.organizationRef`: Value Object opaco, obligatorio en `create()`, fijado en génesis, **inmutable** durante todo el lifecycle. Ningún comando de reasignación existe en el alcance de esta fase.
- `core.domain.fund` sigue sin importar nada de `identity`.
- Dos guardas separadas por responsabilidad: `FundNotAssociatedToOrganizationException` (dominio, dentro de `Fund.execute()`, protege "¿es operable en absoluto?") y `CrossOrganizationAccessException` (Application, compara el identificador subyacente de `Account.organizationId` — nullable — contra `Fund.organizationRef`; cubre tanto "sin organización" como "organización distinta").
- `Fund` v1 (sin `organizationRef`): `reconstitute()`/lectura/rebuild permitidos, comandos de escritura rechazados **permanentemente**. Sin `UNASSIGNED`, sin backfill, sin mecanismo de migración — decisión explícita por ausencia de necesidad de negocio demostrada.

### `ClearFunds` — enmienda a ADR-016
- `ClearFundsAsGenesis` (génesis directa, `sequence = 0`) y `ClearFundsForPledge` (transición sobre `Fund` existente, `sequence > 0`) son dos casos de uso de aplicación distintos.
- `ClearFundsForPledge` **no** recibe `organizationRef`/`campaignRef`/`donorRef`/`currency` como input — se leen del `Fund` reconstituido.
- `FUNDS_CLEARED:v2` mantiene **un único esquema de payload** en ambos caminos — el Application Service rellena los campos ya establecidos cuando el origen es la transición, evitando bifurcación en `EventPayloadRegistry`, JCS y hash chain.

### `Organization ↔ PhysicalAsset` + Donación en Especie (ADR-029)
- `organizationRef`: mismo eje que en `Fund` — autoridad, distinta de `custodianRef` (responsabilidad operativa actual, ADR-003) y `beneficiaryRef` (receptor final, ADR-014).
- **Camino A** (asignación de `Fund`): `organizationRef` es el del `Fund`; `donorRef = null` — el donante financiero **no** se hereda como donante físico (distinción semántica deliberada, no técnica). *(estado posterior)*: la saga que iba a trasladarlo (`FundAllocationSagaPolicy`) fue descartada (ADR-034); el invariante se hace cumplir en `registerPhysicalAsset`, que carga el `Fund` y rechaza cualquier discrepancia con `CrossOrganizationAccessException` antes de autorizar o persistir (ADR-029 §2.1, C3 `014d101`). `AssetRegisteredSagaPolicy` solo confirma/compensa la asignación después del registro.
- **Camino B** (donación en especie): `organizationRef` y `donorRef` como dato de entrada directo, obligatorios. `InKindDonation` **descartado** como Aggregate — sin evidencia de invariante persistible previa al `PhysicalAsset`. Cardinalidad 1:N sin invariante entre hermanos, correlacionados por `donationRef` (generado server-side, correlación pura, sin lifecycle, sin comando propio — Modelo 1: una única invocación de aplicación por acto de donación). Fallo parcial entre hermanos es un estado aceptado, sin compensación.
- `ASSET_REGISTERED:v2` mantiene un único esquema uniforme entre ambos caminos — los campos son `null` según semántica de génesis, nunca por bifurcación de tipo.
- `ASSET_SPLIT`: `organizationRef`, `donorRef` y `donationRef` se heredan íntegros a todo hijo, sin excepción, sin campo `source*` paralelo (a diferencia de `allocationId`/`sourceAllocationId` — no aplica el mismo patrón porque no representan una operación puntual del asset, sino un hecho de procedencia del lote).
- `PhysicalAsset` v1 sin `organizationRef`: mismo tratamiento que `Fund` v1 — lectura/reconstitución sí, escritura permanentemente no.

### `actorRef` (ADR-030 + ADR-031)
- Finalidad de auditoría administrativa, **no** de integridad criptográfica — decisión tomada sobre mérito de negocio (la garantía criptográfica del hash solo protegería la atribución *almacenada*, nunca la autenticidad real del ejecutor, mientras la autenticación siga fuera de alcance).
- No participa en `EventCanonicalMapper`/JCS/`eventHash`/`previousHash`/Merkle. No forma parte de `DomainEventPayload`. El replay puro del Aggregate no lo reconstruye ni depende de él.
- Persistencia: mismo documento MongoDB que el evento, mismo insert atómico — sin ventana de inconsistencia entre "el evento existe" y "la atribución existe". Inmutable **por contrato de persistencia**, explícitamente **no protegido criptográficamente**.
- Sin requisito de negocio demostrado para corregibilidad administrativa — verificado contra los 13 documentos del proyecto, sin evidencia. Si aparece esa necesidad, requiere su propio ADR.
- Taxonomía: `ActorRef` es contrato tipado y extensible, no `enum` cerrado ni `String` plano. Variantes válidas hoy: `SystemActor(policyName)` y `ExternalActor(sourceSystem, externalEventId)` — este último reutiliza `externalEventId` de ADR-013, sin certificar autenticidad del sistema externo. `origin` descartado como concepto independiente — completamente absorbido por la taxonomía.
- **`HumanAccount` diferido** al diseño del mecanismo de escritura autenticada — deliberadamente no se anticipa su forma.

---

## 3. Catálogo de comandos (diseñados en esta sesión)

### `Fund`
- `RegisterFund(organizationRef, campaignRef, donorRef, currency, pledgedAmount, commandId)` — `organizationRef` ahora obligatorio.
- `ClearFundsAsGenesis(organizationRef, campaignRef, donorRef, currency, amount, commandId)`.
- `ClearFundsForPledge(fundId, amount, commandId)` — no recibe datos de identidad del `Fund`, se leen del Aggregate reconstituido.

### `PhysicalAsset`
- `RegisterPhysicalAsset(...)` (Camino A, existente) — su contrato evolucionó durante la ejecución para incluir explícitamente `organizationRef` y `donorRef` o resolverlos según el caso de uso y la política de saga vigente. *(estado posterior)*: firma vigente `registerPhysicalAsset(commandId, fundId, organizationRef, assetType, quantity, unitOfMeasure, custodianRef, currentLocation, allocationId, sourceAllocationId, actorRef)`; `organizationRef` debe coincidir con el del `Fund` y el comando produce el `OutboxMessage` de `ASSET_REGISTRATION_SAGA` (A3/C3).
- `RegisterPhysicalAssetFromDonation(organizationRef, donorRef, donationRef*, assetType, quantity, unitOfMeasure, custodianRef, currentLocation, commandId)` (Camino B) — diseñado con implementación bloqueada en su momento (§5); *(estado posterior)*: implementado como `registerPhysicalAssetFromDonation` (Tarea 5.4). `donationRef` no es parámetro del comando individual, se genera una vez por invocación de Application Service y se reutiliza en cada registro dentro de esa misma llamada.
- `SplitPhysicalAsset(...)` (existente) — no recibe `organizationRef`/`donorRef`/`donationRef`; los tres se heredan del padre.

### Excepciones de dominio nuevas
`FundNotAssociatedToOrganizationException` (dominio, `Fund`), `CrossOrganizationAccessException` (Application).

---

## 4. Modelo de datos — diagrama de cambios sobre Fase 4

```mermaid
graph TB
    subgraph core["Módulo core — colecciones existentes, campos nuevos resaltados"]
        direction TB

        FUND["<b>Fund</b> (event-sourced)
        donorRef
        organizationRef ⭐ nuevo — obligatorio, inmutable
        campaignRef, currency, ..."]

        ASSET["<b>PhysicalAsset</b> (event-sourced)
        custodianRef, beneficiaryRef
        organizationRef ⭐ nuevo — obligatorio, inmutable, heredado en split
        donorRef ⭐ nuevo — nullable (null en Camino A, obligatorio en Camino B)
        donationRef ⭐ nuevo — nullable, solo Camino B, correlación pura
        allocationId / sourceAllocationId (existente, ADR-005/012)"]

        EVT["<b>event_store</b> — documento por evento
        eventId, sequence, payload, previousHash, eventHash
        actorRef ⭐ nuevo — mismo insert, EXCLUIDO del material canonicalizado
        (SystemActor | ExternalActor — HumanAccount diferido)"]
    end

    subgraph identity["Módulo identity — sin cambios en esta fase"]
        direction TB
        ACC["Account
        organizationId (nullable)"]
    end

    FUND -.->|"Application Service compara
    Account.organizationId ↔ Fund.organizationRef"| ACC
    ASSET -.->|"organizationRef opaco — core no importa identity"| ACC

    style core fill:#eef6ff,stroke:#4a7dbd
    style identity fill:#f5f5f5,stroke:#999,stroke-dasharray: 5 5
```

**Notas sobre el diagrama:**
- Ninguno de los tres campos nuevos (`organizationRef` en `Fund`/`PhysicalAsset`, `donorRef`/`donationRef` en `PhysicalAsset`, `actorRef` en el evento) rompe el aislamiento `core ↮ identity` ya establecido en ADR-027 — todos son referencias opacas o metadata de persistencia, nunca tipos importados.
- `actorRef` vive en el mismo documento del evento pero **fuera** del subconjunto de campos que entra a `EventCanonicalMapper` — la línea entre "parte del evento" y "parte de la evidencia criptográfica del evento" queda explícitamente distinta a partir de esta fase.

---

## 5. Bloqueador explícito de implementación (Resuelto)

**`RegisterPhysicalAssetFromDonation` (Camino B, donación en especie) se encontraba originalmente bloqueado; posteriormente fue desbloqueado por HumanActor y actualmente está implementado.**

Por diseño de negocio, es el único comando de génesis del sistema sin `SagaPolicy` que lo dispare automáticamente ni webhook externo asociado — solo puede ser ejecutado por un operador humano. La taxonomía de `ActorRef` cerrada en ADR-031 no tiene hoy ninguna variante legítima para representar esa atribución (`SystemActor`/`ExternalActor` no aplican; `HumanAccount` está diferido). ADR-031 prohíbe explícitamente sustituir esa ausencia con `null`, texto libre o un actor provisional.

```text
ADR-031 → HumanActor implementado vía ADR-035 → autenticación en contracts lista → Camino B → **implementación completada y mergeada (Tarea 5.4)**
```

Esto es una restricción **arquitectónica y normativa**, no una nota de planificación — no depende de en qué orden se ejecute el backlog, depende de que exista una variante formal de `ActorRef` para identidad humana.

**Consecuencia sobre priorización:** donación en especie fue identificada al inicio de esta fase como funcionalidad 🔴 crítica. Su diseño de dominio está completo y aprobado (ADR-029), pero su implementación depende directamente del bloque de autenticación — esto debe reflejarse en cualquier backlog o estimación de fase, no descubrirse durante la ejecución.

---

## 6. Alcance explícitamente pendiente

Actualizado tras el diseño completo de Bloque C/D (§9) — el diseño y la política de negocio conocida están cerrados; lo que sigue pendiente son huecos genuinos descubiertos durante el diseño, no partes del mecanismo sin resolver:

- **Derivación de `organizationRef` para `ExternalActor`** (§9.6) — el webhook de la pasarela de pago puede disparar `CLEAR_FUNDS_AS_GENESIS`/`CLEAR_FUNDS_FOR_PLEDGE`, pero no existe mecanismo diseñado para mapear el contexto del pago externo (campaña, comercio) a un `organizationRef` concreto. Sin este mecanismo, ese camino automatizado no puede construir el comando correctamente — solo queda clara la ruta humana (P7.1-P7.3).
- **Escritura HTTP** — ningún endpoint se diseñó todavía. El mecanismo de autorización (§9) está listo para ser invocado desde un futuro controller, pero ningún endpoint, DTO de request/response, ni framework de exposición (Spring Security o equivalente) se decidió en esta fase.
- **Implementación real** de todo lo diseñado en §9 (P7, P9, P10, P11) — hasta ahora, solo diseño de Modo de Arquitectura, sin código ni tests. *(estado posterior)*: implementado en las Tareas 5.6–5.9 y NUEVA-5; el estado vigente de pendientes está en §12.8.

---

## 9. Bloque C/D — Autorización: mecanismo y matriz de negocio cerrados

Formalizado como **ADR-032 — Approved**. Diseño realizado en la misma sesión de Fase 5, tras cerrar el bloque de dominio. Quedan explícitamente fuera de ADR-032, sin colarse por la puerta trasera: diseño concreto de `HumanAccount`, autenticación, escritura HTTP, derivación de `organizationRef` para `ExternalActor`, y `CommandDispatcher` central.

### 9.1 — P11: puerto `Identity ↔ Core`
- Patrón reutilizado del ya validado para `crypto`/`ai`: **`contracts`** (interfaces + DTOs puros, cero infraestructura) como frontera neutral — no dependencia directa `core ↔ identity` en ningún sentido.
- `IdentityPrincipalPort` (interfaz, en `contracts`) + `AuthorizationPrincipal` (DTO): `accountId: String` (opaco), `organizationId: String?` (opaco, nullable), `roles: Set<AuthorizationRole>`.
- `AuthorizationRole`: tipo **propio del contrato**, no espejo de `identity.domain.Role` — anti-corruption layer explícito. Mapeo `identity.domain.Role → AuthorizationRole` vía `switch` expression **exhaustivo, sin `default`** (Java 21) — un rol nuevo en Identity sin clasificar deja de compilar, no falla en runtime. Corrección semántica del mapeo (`ADMINISTRATOR → ADMINISTRATOR`, no un typo) queda cubierta por unit test aparte — exhaustividad y corrección son propiedades distintas.
- Nueva arista de dependencia: `identity → contracts` (antes evitada por "sin necesidad real de comunicación cruzada", ADR-027 — ahora justificada). `identity → core` y `core → identity` permanecen ambas inexistentes.
- `accountId`/`organizationId` permanecen `String` sin Value Object contractual nuevo — son identificadores opacos que se transportan, no conceptos que requieran reinterpretación entre módulos (a diferencia de `roles`, que sí traduce un modelo de capacidades a un modelo de autorización).

### 9.2 — P9: determinación de pertenencia organizacional
- `OrganizationBoundaryPolicy` — componente **stateless** en `core.application.authorization`, sin conocer ningún Aggregate concreto. Firma: `assertBelongs(principalOrganizationId: String?, resourceOrganizationRef: String)`.
- Centralizado deliberadamente (no duplicado por Application Service) porque cumple los cuatro criterios que justifican centralizar una guarda de seguridad: semántica normativa idéntica en todos sus puntos de aplicación, misma excepción, orden de evaluación obligatorio, y una divergencia accidental tendría consecuencia de autorización, no solo de mantenimiento.
- La **extracción** de `organizationRef` sigue siendo responsabilidad de cada `*CommandService` (ya tiene el Aggregate cargado) — no se introduce ninguna interfaz compartida (`HasOrganizationRef` o similar) entre `Fund` y `PhysicalAsset`: eso reabriría, sin necesidad demostrada, la segregación de Aggregates de ADR-001.
- `null` en cualquiera de los dos lados, o mismatch, produce `CrossOrganizationAccessException` — misma excepción para ambos casos, sin variantes locales.
- Se evalúa **antes** que el rol. Ningún rol, actual o futuro, tiene bypass de esta guarda — un bypass cross-organization requeriría una nueva decisión arquitectónica explícita, no una excepción implícita en la matriz de P7.

### 9.3 — P10: composición
```text
Application Service
        │
        ├── 1. OrganizationBoundaryPolicy.assertBelongs(...)
        │         └── falla → CrossOrganizationAccessException (fin, rol no se evalúa)
        │
        ├── 2. RoleAuthorizationPolicy.authorize(principal, CommandType.X)
        │         └── falla → excepción de autorización por rol (fin)
        │
        └── 3. Aggregate.execute(command)
```
Composición estrictamente secuencial, ambas guardas en `core.application`, ninguna en `core.domain`. Estructural — la composición queda cerrada independientemente de los valores concretos de la matriz de P7. La matriz ya está completa en §9.5; la separación entre composición y política de negocio se conserva porque son decisiones arquitectónicas distintas.

### 9.4 — P7, mecanismo (distinto de la matriz, ver §9.5)
- `CommandType`: `enum` cerrado en `core.application.authorization` (`REGISTER_FUND`, `CLEAR_FUNDS_AS_GENESIS`, `CLEAR_FUNDS_FOR_PLEDGE`, `REGISTER_PHYSICAL_ASSET`, `REGISTER_PHYSICAL_ASSET_FROM_DONATION`, `SPLIT_PHYSICAL_ASSET`) — clave primaria de la matriz (`CommandType → roles`, no `Role → comandos`, porque expresa mejor la pregunta que resuelve: "para ejecutar esta operación, ¿qué roles bastan?").
- `RoleAuthorizationPolicy.authorize(principal, commandType)`: `switch` expression exhaustivo sobre `CommandType`, sin `default` — mismo mecanismo que el mapeo de roles en P11, aplicado aquí para que un `CommandType` nuevo sin política asignada no compile.
- Autorización declarada **centralizadamente**, no vía `requiredRole()` en cada `Command` ni anotaciones distribuidas — un `Command` representa una intención de negocio, no la política de quién puede ejecutarla; mezclarlas dificultaría auditar la política completa sin recorrer cada clase de comando.
- Hueco distinto de la exhaustividad del `switch`: nada garantiza hoy que todo `*CommandService` **invoque** la política antes de tocar el Aggregate. Mitigación elegida: regla de ArchUnit que verifica la integración estructural obligatoria en los `*CommandService` — no se introduce un `CommandDispatcher` central todavía (cambiaría la forma de entrada de todos los comandos; alternativa anotada para si la cantidad de servicios lo justifica más adelante, no antes).
- Verificación de orden real (pertenencia evaluada antes que rol, en cada llamada) queda para tests de comportamiento (`InOrder` de Mockito o equivalente) — ArchUnit prueba presencia estructural, no secuencia de ejecución.

### 9.6 — Alcance de P7 y P9 por tipo de actor

Hallazgo posterior al cierre inicial de P7/P9: ninguna de las dos guardas se generaliza sin más a `SystemActor`/`ExternalActor` — ambas son controles sobre una identidad humana que presenta rol/pertenencia frente a un recurso, y ese par de valores no siempre existe.

| Actor | P7 (`RoleAuthorizationPolicy`) | P9 (`OrganizationBoundaryPolicy`) | Fuente de legitimidad/coherencia |
|---|---|---|---|
| `HumanAccount` | Sí | Sí | Roles (matriz §9.5) + pertenencia organizacional |
| `SystemActor` | No | No | Causalidad interna — la saga transporta/hereda un `organizationRef` ya establecido en el recurso de origen (ADR-029); no hay dos organizaciones que comparar, solo una que se propaga. *(estado posterior)*: en `registerPhysicalAsset` el bypass de P7/P9 no exime del invariante del Camino A, que se valida contra el `Fund` para cualquier actor (ADR-029 §2.1) |
| `ExternalActor` | No | No | Autenticación de frontera de integración — pero **la derivación confiable de `organizationRef` desde el contexto externo (ej. qué campaña/comercio del webhook de pago corresponde a qué `Organization`) queda pendiente, sin mecanismo diseñado** |

Las cinco (y, tras P7.6, seis) celdas de la matriz de §9.5 se leen con este alcance: *"cuando el `CommandType` es ejecutado por un `HumanAccount`, ese humano debe tener el rol indicado"* — no como una regla universal que todo actor deba satisfacer.

### 9.5 — P7, matriz de autorización: completa

**Las seis celdas quedan decididas, cada una con justificación independiente.** Verificado contra el catálogo completo de los 13 documentos del proyecto antes de empezar: no existía evidencia documental que vinculara `identity.domain.Role` con autorización de comandos de `core` — esta sesión fue la primera vez que ambos se conectaron. Ninguna celda se completó por intuición ni por analogía entre comandos; cada una fue evaluada de forma independiente, incluso cuando el resultado coincidió con la celda anterior.

```text
REGISTER_FUND                          → {ADMINISTRATOR}  ✅ decidido
    (REPRESENTATIVE excluido deliberadamente: RegisterFund crea un compromiso
     PLEDGED preparatorio, no mueve fondos reales — CLEAR_FUNDS_FOR_PLEDGE lo hace;
     EMPLOYEE excluido por ausencia de evidencia de responsabilidad financiera en ese rol)
CLEAR_FUNDS_AS_GENESIS                 → {ADMINISTRATOR}  ✅ decidido
    (REPRESENTATIVE y EMPLOYEE excluidos deliberadamente: constituye entrada real de
     fondos sin promesa previa — sequence=0, FUNDS_CLEARED:v2 directo — sin evidencia
     documental que atribuya esa responsabilidad financiera a ninguno de los otros dos roles)
CLEAR_FUNDS_FOR_PLEDGE                 → {ADMINISTRATOR}  ✅ decidido
    (REPRESENTATIVE y EMPLOYEE excluidos deliberadamente: confirma la materialización
     efectiva de una promesa ya registrada — clearedAmount aumenta, fondos quedan
     disponibles — sin evidencia de que representación organizacional o función
     operativa impliquen autoridad de conciliación financiera)
REGISTER_PHYSICAL_ASSET                → {EMPLOYEE}  ✅ decidido
    (ADMINISTRATOR y REPRESENTATIVE excluidos deliberadamente: es un registro
     operativo/logístico de un recurso físico ya asignado financieramente —
     ni ADMINISTRATOR ni REPRESENTATIVE tienen evidencia documental de
     responsabilidad sobre el alta física de activos; no se transfiere el
     rol de los comandos financieros anteriores por continuidad)
REGISTER_PHYSICAL_ASSET_FROM_DONATION  → {EMPLOYEE}  ✅ decidido — bloqueado de implementación por ADR-031/HumanAccount
    (ADMINISTRATOR y REPRESENTATIVE excluidos deliberadamente: sigue siendo génesis
     física/logística ejecutada por un humano; donorRef/organizationRef directos y
     donationRef distinguen procedencia y correlación, pero no aportan evidencia de
     responsabilidad financiera o administrativa que cambie el rol respecto de P7.4;
     el bloqueo de implementación es independiente de esta decisión de rol)
SPLIT_PHYSICAL_ASSET                   → {EMPLOYEE}  ✅ decidido
    (ADMINISTRATOR y REPRESENTATIVE excluidos deliberadamente: transforma la
     genealogía de un recurso ya existente conservando organizationRef/donorRef/
     donationRef sin cambios — no introduce autoridad financiera, organizacional
     ni administrativa nueva; es la misma responsabilidad operativa que P7.4/P7.5,
     evaluada independientemente, no copiada por continuidad)

Extensiones confirmadas (A2):
REQUEST_ALLOCATION                     → {ADMINISTRATOR} ✅ decidido (A2, con aprobación humana explícita ya registrada)
    (Protege el invariante availableBalance >= allocationAmount; mismo tipo de invariante
     financiero que REGISTER_FUND/CLEAR_FUNDS_AS_GENESIS/CLEAR_FUNDS_FOR_PLEDGE).
CONFIRM_ALLOCATION                     → {ADMINISTRATOR} ✅ decidido (A2, con aprobación humana explícita ya registrada)
    (Consolida el compromiso financiero abierto por REQUEST_ALLOCATION; mismo nivel de exposición).
DELIVER_ASSET                          → {EMPLOYEE} ✅ decidido (A2, con aprobación humana explícita ya registrada)
    (Transición terminal de custodia física, sin impacto financiero; mismo tipo que REGISTER_PHYSICAL_ASSET).

Nota: REVERSE_ALLOCATION_ADMINISTRATIVELY es una octava entrada preexistente a A2 (no forma parte del "seis de seis" original ni de esta extensión de 3). Se deja constancia explícita de que esta extensión se evaluó con rigor individual, no por generalización, citando textualmente la advertencia existente en el documento: "todo comando debe evaluarse con el mismo rigor individual, no asumiendo la agrupación observada aquí".
```

**Matriz P7 completa — decidida para todos los comandos.** Lectura global: dos fronteras semánticas, no decisiones aisladas — comandos de `Fund` (financieros) → `{ADMINISTRATOR}`; comandos de `PhysicalAsset` (operativos/logísticos) → `{EMPLOYEE}`. Ningún comando autoriza a `REPRESENTATIVE` en el estado actual — ausencia deliberada en las celdas, registrada explícitamente, no un olvido. Esta agrupación es un **resultado observado del análisis celda por celda**, no una regla general que se haya declarado y aplicado — no existe en ningún documento del proyecto una política que diga "EMPLOYEE gestiona todo PhysicalAsset" o "ADMINISTRATOR gestiona todo Fund"; todo comando debe evaluarse con el mismo rigor individual, no asumiendo la agrupación observada aquí.

**Insumo de negocio que se usó para completar la matriz** (las tres preguntas planteadas al abrir §9.5, ya resueltas comando por comando):
1. Naturaleza del compromiso de cada operación — distinguió comandos preparatorios/financieros efectivos (`Fund`) de comandos operativos/logísticos (`PhysicalAsset`).
2. Responsabilidades reales de `EMPLOYEE` — confirmadas como operativas/logísticas, sin responsabilidad financiera, en las tres celdas de `PhysicalAsset`.
3. Grado de concentración — no se introdujo ninguna regla de cardinalidad; la matriz determina rol habilitado, no número de personas.

Ninguna celda se resolvió con una regla general tipo `ADMINISTRATOR > REPRESENTATIVE > EMPLOYEE` — P9 estableció que el rol determina capacidad dentro del ámbito organizacional, nunca alcance, y cada una de las seis decisiones se evaluó de forma independiente, incluso cuando el resultado coincidió con la celda anterior.

---

## 7. Trazabilidad de las 12 preguntas fundacionales de Fase 5

Al abrir Fase 5 se fijaron 12 preguntas de arquitectura como punto de partida. Con el cierre del bloque de dominio y del Bloque C/D, las 12 preguntas fundacionales quedan formalmente cerradas.

| # | Pregunta | Estado |
|---|---|---|
| 1 | Papel de `Organization` respecto de `Fund` | ✅ Cerrada — ADR-028 |
| 2 | Papel de `Organization` respecto de `PhysicalAsset` | ✅ Cerrada — ADR-029 |
| 3 | ¿Qué es exactamente una donación en especie? | ✅ Cerrada — ADR-029, Camino B |
| 4 | ¿Quién es el donor de una donación física? | ✅ Cerrada — ADR-029, `donorRef` propio del Camino B |
| 5 | ¿Qué Aggregate representa la donación en especie? | ✅ Cerrada — `PhysicalAsset`; `InKindDonation` descartado |
| 6 | ¿Cómo se relaciona con `PhysicalAsset`? | ✅ Cerrada — camino de génesis + `donationRef` de correlación |
| 7 | ¿Quién puede crear/modificar cada recurso? | ✅ Cerrada — matriz completa (§9.5) |
| 8 | ¿Qué significa `actorId` tras Identity? | ✅ Cerrada — ADR-030/031 |
| 9 | ¿Cómo se determina que una `Account` puede operar sobre un recurso? | ✅ Cerrada — `OrganizationBoundaryPolicy` (§9.2), scoped a `HumanAccount` (§9.6) |
| 10 | ¿Dónde vive la autorización? | ✅ Cerrada — composición secuencial en Application Service (§9.3) |
| 11 | ¿Cómo cruza Identity hacia core sin romper las fronteras? | ✅ Cerrada — `IdentityPrincipalPort`/`AuthorizationPrincipal` vía `contracts` (§9.1) |
| 12 | ¿Qué parte de escritura HTTP pertenece a Fase 5? | ✅ Cerrada — ninguna |

**Resultado: 12 de 12 cerradas.** Dos huecos genuinos permanecen, pero son distintos de las 12 preguntas fundacionales — son consecuencias descubiertas durante el diseño de Bloque C/D: la forma de `HumanAccount` (diferida deliberadamente, ADR-031) y el mecanismo de derivación de `organizationRef` para `ExternalActor` (§9.6, sin diseño, pendiente explícito).

## 8. Siguiente paso

*(Registro histórico del momento de cierre del diseño. Los dos pasos descritos ya se ejecutaron; ver §10 para las tareas y §12.8 para el estado vigente.)*

El diseño de Fase 5 (bloque de dominio + Bloque C/D) está completo y formalizado — cuatro ADRs de dominio (028-031), más la enmienda a ADR-016, ADR-032 de autorización, ADR-033 sobre la saga de registro de activos, y ADR-034 sobre la visibilidad de allocations pendientes. Quedan dos acciones, en este orden:

1. **Iniciar implementación** — orden de diseño: P11 (puerto) → P9 (`OrganizationBoundaryPolicy`) → P7 (`CommandType`/`RoleAuthorizationPolicy`/ArchUnit) → P10 (wiring en los `*CommandService` existentes).
2. Dos pendientes explícitos que **no bloquean lo anterior** pero sí bloquean funcionalidad específica: `HumanAccount` (bloquea `RegisterPhysicalAssetFromDonation`, §5) y la derivación de `organizationRef` para `ExternalActor` (bloquean que el webhook de pago pueda construir y disparar `CLEAR_FUNDS_*` con un `organizationRef` confiable; mientras ese mecanismo no exista, la ruta humana es la única ruta actualmente diseñada de forma completa para esos comandos — no porque el webhook sea conceptualmente imposible, sino porque su mecanismo de derivación no se ha diseñado todavía).

---

## 10. Hallazgo de implementación — auditoría de preexistencia (Tareas 5.0, 5.1, 5.3)

Durante la implementación de la Tarea 5.3 se descubrió, con evidencia literal (`grep`/`find` contra el repositorio real, documentado en `auditoria-preexistencias-fase5.md`), que `documento-maestro-proyecto.md` §7.1 describía una capa de aplicación sustancialmente más completa de lo que existía en código. Tres afirmaciones del documento maestro (o de prompts de tarea basados en él) resultaron falsas al verificarlas:

1. `EventEnvelopeFactory` no existe (Tarea 5.0) — el ensamblado del envoltorio ocurre en `MongoEventStoreAdapter.append()`. Sin impacto en el diseño, solo cambió el punto de implementación de `actorRef`.
2. `FundAllocationSagaPolicy` no existía — solo `AssetRegisteredSagaPolicy` era real. `SplitPhysicalAssetSagaPolicy`, también citada por el maestro, tampoco existía.
3. Los comandos de aplicación `registerFund`, `clearFunds`/`clearFundsGenesis`, `requestAllocation`, `registerPhysicalAsset`, `splitPhysicalAsset` no existían en `FundCommandService`/`PhysicalAssetCommandService`. Solo existían `confirmAllocation`, `reverseAllocation` (`FundCommandService`) y `deliverAsset` (`PhysicalAssetCommandService`) — exactamente los métodos que `AssetRegisteredSagaPolicy` ya necesitaba.

**Conclusión de la auditoría original:** el flujo de asignación `Fund → PhysicalAsset` de ADR-012 estaba parcialmente implementado — los payloads y la lógica de dominio existían (`Fund.requestAllocation()`/`confirmAllocation()`/`reverseAllocation()` producen eventos reales), pero no existía ningún punto de entrada de aplicación para génesis de `Fund`, ni la saga que conecta la solicitud de asignación con el registro del activo, ni los comandos de aplicación de `PhysicalAsset` más allá de la entrega.

**Decisión:** detener el backlog (Opción A, no acumular deuda técnica) e insertar el **Bloque 2bis** en `plan-ejecucion-agentes-fase5.md`, con cuatro tareas nuevas como prerrequisito de 5.3/5.4/5.5:

```text
NUEVA-1  feat/core-fund-genesis-commands              (registerFund, clearFundsGenesis)
NUEVA-2  feat/core-fund-request-allocation-command    (requestAllocation) — depende de NUEVA-1
NUEVA-3  feat/core-physicalasset-application-commands (registerPhysicalAsset, splitPhysicalAsset)
NUEVA-4  feat/core-pending-allocation-read-model      (“Visibilidad y operabilidad manual de PENDING_ALLOCATION”) — PARTE A desbloqueada, PARTE B bloqueada
NUEVA-5  feat/core-human-actor-authorization          (HumanActor como variante de ActorRef) — IMPLEMENTACIÓN COMPLETADA
```

**Aclaración histórica sobre NUEVA-2:** `NUEVA-2` (`feat/core-fund-request-allocation-command`): la rama existía inicialmente con un commit que preservaba el DISEÑO del test de integración (`FundCommandServiceAllocationIntegrationTest.java`, 7 casos de prueba), rescatado durante un incidente de la Tarea Bug Saga 1, y no representaba implementación en curso. Esto ha sido resuelto en la implementación posterior.

**Estado actual de las Tareas (Verificado contra develop 16a3c36):**
- **5.0** (actorref-mechanism): COMPLETADA
- **5.1** (fund-organization-ref): COMPLETADA
- **NUEVA-1** (core-fund-genesis-commands): COMPLETADA (Implementación introducida en el commit `0579f41`, integrado directamente en el historial base de develop anterior al branch actual).
- **NUEVA-2** (core-fund-request-allocation-command): COMPLETADA (Implementación en 299f04b, mergeada vía PR #6 en 927eefa).
- **NUEVA-3** (core-physicalasset-application-commands): COMPLETADA (Implementación en 0568325, mergeada vía PR #4 en 66fc813).
- **NUEVA-4** (core-pending-allocation-read-model / core-nueva-4b-reverse-allocation): **COMPLETADA**
  - **Redefinida:** “Visibilidad y operabilidad manual de PENDING_ALLOCATION”
  - **PARTE A:** Read model/proyección de allocations pendientes → **COMPLETADA**
  - **PARTE B:** Resolución administrativa de `reverseAllocation` (NUEVA-4B) → **COMPLETADA Y MERGEADA** (PR #25)
  - *Descartados:* `FundAllocationSagaPolicy`, TTL/expiración automática, `reason` en compensación. `PhysicalAsset` queda fuera de NUEVA-4.
- **NUEVA-5** (core-human-actor-authorization): **COMPLETADA** (HumanActor, ADR-035 y wiring implementados y mergeados en PR #23, #24).
- **5.2** (fund-clearfunds-split): COMPLETADA
  - comando agregado en FundCommandService
  - recibe commandId, fundId, amount, sourceRef y actorRef
  - usa processedCommandRepository para el guard inicial
  - usa retryTemplate
  - rehidrata Fund desde EventStore
  - conserva expectedVersion
  - ejecuta Fund.clearFunds(amount, sourceRef)
  - publica FUNDS_CLEARED mediante appendAndOutbox
  - actorRef propagado
  - idempotencia garantizada por el mecanismo existente de TransactionalEventPublisher / tryClaim
  - test de integración con 5 casos
  - suite core validada: 145 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS
- **5.3** (physicalasset-organization-donor-ref): **COMPLETADA** (Camino A, PR #13)
- **5.4** (physicalasset-donation-genesis-domain / in-kind-donation): **COMPLETADA** (Soporte de dominio PR #15 y capa de aplicación PR #26)
- **5.5** (physicalasset-split-inheritance): **COMPLETADA** (Herencia de dominio y aplicación mergeadas en PR #19). La orquestación de creación y persistencia del stream del activo hijo está explícitamente pendiente como deuda técnica.
- **5.6** (contracts-identity-principal-port): **COMPLETADA** (PR #14)
- **5.7** (organization-boundary-policy): **COMPLETADA** (PR #20)
- **5.8** (role-authorization-policy): **COMPLETADA** (PR #16)
- **5.9** (authorization-wiring): **COMPLETADA** (PR #21)
- **5.10** (fase5-integration-tests): **COMPLETADA**. Validación histórica de la integración del alcance aprobado, con evidencia reproducible obtenida sobre el hito 87af002. Existen deudas explícitamente diferidas hacia el trabajo posterior de Fase 6, abandonando afirmaciones de E2E completo sin matices.
- **5.11** (cierre documental y formal): **COMPLETADA** (hito histórico `87af002`). La evidencia de tests vigente tras la remediación del Bloque A está en §12.7 (417 tests, reactor completo).

**Aclaración sobre Asimetría de NUEVA-2 (`requestAllocation`):**
El método `requestAllocation()` de `FundCommandService` incluye la guarda explícita `exists(commandId)` para prevenir repeticiones del mismo comando. A diferencia de este, `confirmAllocation()` y `reverseAllocation()` NO usan esa guarda. La justificación de esta asimetría es:
- `requestAllocation()` dispara `DuplicateAllocationException` a nivel de dominio para una asignación ya existente. Esta excepción NO hereda de `RedundantDomainActionException`. Por lo tanto, `CommandRetryTemplate` NO la absorbe, y un reintento secuencial del mismo comando fallaría sin la guarda `exists(commandId)`.
- `confirmAllocation()` y `reverseAllocation()` tienen otra ruta histórica donde el dominio sí lanza excepciones que heredan de `RedundantDomainActionException` al repetir la operación. El template intercepta y absorbe estas excepciones, por lo que no requieren la guarda secuencial adicional.

*(estado posterior, A7.1 `e10a3ca`)*: la familia `Redundant*` fue eliminada y `CommandRetryTemplate` ya no absorbe nada salvo `ConcurrencyConflictException`. `confirmAllocation()`/`reverseAllocation()` siguen sin la guarda `exists(commandId)`, pero su idempotencia es ahora explícita: la redundancia exacta es un no-op de dominio (cero eventos) y el Application Service continúa hasta `appendAndOutbox(emptyList, ..., commandId)`, donde `tryClaim` deduplica el mismo `commandId` y registra uno distinto como procesado. Cubierto por `ProcessedCommandIdempotencyIntegrationTest` (escenarios A–C).

---

## 11. Estado de Bug Sagas y Hallazgos Transversales

**Bug Saga 1** (`fix/core-saga-outbox-idempotency-guard`) — CERRADO, mergeado a develop en el commit `1b3930a` (PR #3). `tryClaim()` vía `findAndModify`+`upsert`+`setOnInsert` dentro de `TransactionalEventPublisher.appendAndOutbox()`, migrados `confirmAllocation`/`reverseAllocation`.

**Bug Saga 2 y Bug Saga 3:** SIN INICIAR, confirmado sin código ni ramas activas.

**Hallazgo Transversal (CommandRetryTemplate) — ✅ CERRADO técnicamente (A7.1, `e10a3ca`; ver §12.8):**
Hallazgo original: `CommandRetryTemplate` absorbe `RedundantDomainActionException` (retornando `null` como "éxito" aparente) INDEPENDIENTEMENTE del `commandId`.
- Es comportamiento preexistente en la base de código.
- No fue introducido por Bug Saga 1.
- No fue introducido por NUEVA-2.
- Genera un falso positivo si dos comandos con **distinto** `commandId` (por ejemplo, repetición maliciosa o nuevo intento genuino sin correlación adecuada) alcanzan el mismo estado redundante en el agregado.

**Hallazgo Transversal (Projection Retry Framework):**
Se detectó un defecto preexistente en el mecanismo compartido de reintento de proyecciones (CQRS). Ver `hallazgo-framework-retry-projections.md` para el detalle técnico.
- Consiste en un riesgo de pérdida silenciosa de eventos cuando fallan durante el reprocesamiento del `ProjectionRetryScheduler`.
- **Estado:** ✅ CERRADO (A7.2, `e4404e5` + C4 `20ba931`; decisión formalizada en ADR-042).
- **Alcance:** FUERA DE NUEVA-4.

**Deuda Técnica Diferida (Productor de Outbox de Negocio - 5.10) — ✅ CERRADA (A3 + C3, ver §12.5):**
`registerPhysicalAsset()` y otras operaciones de negocio no generan actualmente el `OutboxMessage` porque no existe un productor real en la capa de aplicación (invocan `appendAndOutbox()` con `List.of()` vacío).
- **Alcance afectado:** La saga E2E `ASSET_REGISTRATION_SAGA` (Camino A) y cualquier otra transacción que dependa de Outbox.
- **Estado:** DEUDA TÉCNICA DIFERIDA para trabajo posterior. La prueba E2E de 5.10 inyectó el mensaje manualmente para validar el resto del flujo, pero la implementación del productor real pertenece a la fase siguiente. NO es un bug descubierto sorpresivamente; es una limitación aplazada explícitamente.

**Deuda Técnica Diferida (Creación del Stream Hijo en Split):**
La Tarea 5.5 se completó estrictamente dentro de su alcance original, el cual exigía exclusivamente que el evento emitido por el *padre* heredara los tres identificadores (`organizationRef`, `donorRef`, `donationRef`).
- El DoD original de 5.5 NO exigía la orquestación y creación del stream independiente del agregado hijo en EventStore.
- **Estado:** DEUDA TÉCNICA DIFERIDA. La inicialización del stream del hijo queda explícitamente aplazada para trabajo posterior. Esto no constituye un incumplimiento de la 5.5, sino el reconocimiento de un alcance que nunca fue abarcado.

**Observación Futura (`requestAllocation` sin autorización) — ✅ RESUELTA en A2 (`7be6751`):** hoy `requestAllocation` invoca `authorize(..., CommandType.REQUEST_ALLOCATION)`. Texto original:
El comando `requestAllocation` actualmente no implementa `authorize()`. Dado que hoy no existe ningún entrypoint humano (HTTP/GraphQL) que lo exponga, no representa una vulnerabilidad crítica explotable, pero se documenta formalmente como observación/deuda para incorporarle la política de roles en revisión futura.

---

## 12. Cierre Histórico, Auditoría y Remediación Técnica

### 12.1. Cierre Histórico Previo de Fase 5
Históricamente, con la ejecución de la Tarea 5.11 (hito `87af002`), la Fase 5 fue registrada formalmente como cerrada en su alcance original:
- **Cierre histórico anterior:** Registrado técnicamente en `a6c9ebd`, y formalizado a nivel documental en `87af002`.
- **Resultado de tests del hito histórico:** `BUILD SUCCESS`. Se obtuvo evidencia reproducible sobre el hito exacto `87af002` mediante `mvn clean test`, validando el reactor y el módulo `core` aislado (191 tests, 0 failures/errors). Este estado verde reflejó el cierre original previo al trabajo de Fase 6 en `develop`.
- **Tareas del alcance histórico original:** Tareas 5.0 a 5.5 (Bloque de Dominio), 5.6 a 5.9 (Autorización), 5.10 (Integración E2E sobre el alcance aprobado) y NUEVA-1 a NUEVA-5 / 4B.
- **Deudas técnicas explícitamente diferidas en el cierre histórico:**
  - *Productor real de Outbox:* Su ausencia impide un flujo E2E sin intervención manual en operaciones de negocio.
  - *Stream del Hijo en Split:* Orquestación de persistencia del agregado hijo pendiente (fuera del DoD original de 5.5).
  - *`requestAllocation` sin `authorize()`:* Diferido para revisión futura por no tener entrypoint externo.
  - *`FundCommandService.reverseAllocation()`:* Entrypoint interno de compensación sin `authorize()`. ADR-036 lo contempla como SystemActor, pero el código no restringe el tipo en runtime y no existe entrypoint HTTP/humano actual.
  - *Derivación de organizationRef para ExternalActor:* Sin mecanismo de webhook para derivarlo.
- **ADRs relevantes de Fase 5:** ADR-034, ADR-035, ADR-036 — Reversión Administrativa de Asignación (**Approved**); ADR-033 (**Aprobado parcialmente**). *Aviso de Colisión Documental*: ver §1 — colisiones 033–037 pendientes de decisión humana, ADR-038 supersedido, ADR-039 pendiente de numeración, ADR-042 creado (A7.2).
- *(estado posterior)*: de las deudas anteriores, el productor real de Outbox quedó cerrado (A3/C3) y `requestAllocation` tiene `authorize()` (A2). Las restantes siguen vigentes; ver §12.8.

### 12.2. Auditoría Posterior de Fase 5
Tras el cierre histórico, una auditoría técnica forense realizada sobre el repositorio identificó deudas técnicas, inconsistencias documentales y omisiones de manejo de excepciones en el Bloque A (A1 a A7.2), concluyendo que se requería un proceso formal de remediación antes de consolidar definitivamente el ciclo de Fase 5.

### 12.2.1. Bloque A2: Auditoría de autorización de comandos

Se auditó exclusivamente la superficie de los siguientes métodos:

- `FundCommandService.requestAllocation`
- `PhysicalAssetCommandService.deliverAsset`
- `FundCommandService.confirmAllocation`
- `FundCommandService.reverseAllocation`

#### Resultado

| Método | Estado actual | Clasificación |
|---|---|---|
| `requestAllocation` | Invoca `authorize(..., CommandType.REQUEST_ALLOCATION)` (A2, `7be6751`). Sin entrypoint HTTP. | ✅ |
| `deliverAsset` | Invoca `authorize(..., CommandType.DELIVER_ASSET)` (A2, `7be6751`). Sin entrypoint HTTP. | ✅ |
| `confirmAllocation` | Invoca `authorize(..., CommandType.CONFIRM_ALLOCATION)` (A2, `7be6751`). Invocado hoy por `AssetRegisteredSagaPolicy` como `SystemActor` (bypass P7/P9). | ✅ |
| `reverseAllocation` | Operación interna de compensación invocada por `AssetRegisteredSagaPolicy` mediante `SystemActor`. Sin `authorize()` **por diseño** (ADR-036 §2; regresión corregida en `8356373`, documentada en código y cubierta por test en C2 `c07f994`). La operación humana es `reverseAllocationAdministratively`. | B (por diseño) |

*(Tabla verificada contra el código en C6. La matriz de roles de las tres extensiones está en §9.5.)*

#### Hallazgos remanentes (A2)
- `reverseAllocation` no restringe en runtime el tipo de `ActorRef`: la exclusión de humanos depende de que no exista entrypoint humano. Deuda aceptada mientras no haya exposición HTTP.
- `confirmAllocation`/`reverseAllocation` no tienen la guarda previa `exists(commandId)`. Tras A7.1 no es un defecto: la idempotencia la garantiza `tryClaim` dentro de `appendAndOutbox` (ver §10, nota posterior sobre la asimetría de NUEVA-2).

### 12.3. Estado de la Rama al Inicio de la Remediación Técnica (Bloque A) — registro histórico
*(Instantánea del inicio de la remediación. El estado vigente de la rama y del Bloque A está en §12.8.)*
- **Rama activa de trabajo (en ese momento):** `fix/fase5-cierre-bloque-a`.
- **Estado base real:** Commit `793d4b8` (`develop == origin/develop`), punto de partida oficial y única fuente de verdad para esta remediación.
- **Evidencia histórica del estado base:** En el commit base `793d4b8`, el working tree original se encontraba completamente limpio (`git diff --check` limpio).
- **Estado de trabajo actual:** La rama contiene modificaciones locales activas sin commit correspondientes a la primera etapa de remediación:
  - *A1 — Contexto de Test Acotado (MongoUnanchoredEventAdapterTest):* Verificado en `793d4b8` y documentado como CERRADO en el plan de corrección (3/3 tests ejecutados de forma autónoma con éxito).
  - *A4 — Reconstrucción de ADR-028 a ADR-032:* Archivos físicos `ADR-028-relacion-organization-fund.md` a `ADR-032-autorizacion-comandos-core-matriz.md` reincorporados en `Documentos/`, respaldados por evidencia directa en código, tests e historial Git.
  - *A5 — Manejo de Excepciones en Proyecciones:* Eliminado el silencio de error en `DonationProjectionHandler.java` con diferenciación entre `IllegalArgumentException` (`QUARANTINED`), `DataAccessException` (`PENDING`) y `Exception` (`QUARANTINED`), respaldado por `DonationProjectionHandlerExceptionHandlingTest.java` (4/4 tests unitarios pasando; reactor `core` con 198 tests exitosos).
- **Tareas pendientes de Bloque A (A2, A3, A6, A7.1, A7.2):** Se mantienen delimitadas sin cambios en esta etapa, a la espera de las decisiones técnicas y de contrato previstas en el plan.

**Conclusión y Contextualización:**
La declaración de que "la Fase 5 queda formalmente cerrada" corresponde al hito histórico y documental previo (Tarea 5.11 en `87af002`), el cual delimitó las deudas diferidas conocidas. El trabajo actual sobre la rama `fix/fase5-cierre-bloque-a` no afirma que dicho ciclo esté terminado, sino que constituye precisamente el proceso activo de remediación técnica de las observaciones detectadas con posterioridad, garantizando la consistencia demostrable entre documentación, pruebas y código de producción.

### 12.4. Hallazgo de Regresión A2 (ADR-036) — ✅ Cerrado
**Descripción:** La implementación original de A2 introdujo incorrectamente una llamada a `authorize(...)` dentro de `FundCommandService.reverseAllocation(...)`. Esto violaba directamente el ADR-036, el cual congela explícitamente ese método ordenando que "permanece inalterado".
**Riesgo asociado:** El único invocador en producción de este método es la compensación de la saga (`AssetRegisteredSagaPolicy`) operando como `SystemActor`. Ya que las políticas de roles/organizaciones no aplican a `SystemActor` (como se detalla en §9.6), esta guarda espuria amenazaba con romper la compensación automatizada en producción.
**Corrección:** Se revirtió literalmente la guarda en `reverseAllocation(...)` devolviéndolo a su estado original (sin autorización). Se añadió el test de regresión `reverseAllocation_systemActor_noAuthorizationException` invocando exitosamente como `SystemActor`.
**Resultado:** `mvn test -pl core` en verde (206/206 tests superados en ese momento).

### 12.5. Productor de Outbox para ASSET_REGISTRATION_SAGA (A3) — ✅ Cerrado (con dependencias)
**Descripción:** Se sustituyó la inyección manual de `OutboxMessage` en tests E2E por un productor real dentro de `PhysicalAssetCommandService.registerPhysicalAsset(...)`.
**Cumplimiento del Contrato:** El productor genera el mensaje respetando al 100% los 4 campos esperados por `AssetRegisteredSagaPolicy`:
1. `correlationId` = `fundId`
2. El `payload` contiene exactamente `{"allocationId":"...", "fundId":"..."}`
3. `sourceAggregateId` = `assetId` (el activo registrado, no el fondo)
4. El `messageId` se genera de forma independiente y no reutiliza el `commandId`.
**Manejo de Excepciones:** Se corrigió el uso genérico de `IllegalArgumentException` por una excepción de dominio nombrada `InvalidFundReferenceException` (regla 2.6).
**Deuda técnica preexistente confirmada (en el momento de A3):** `registerPhysicalAsset(...)` **solo validaba formato/no-vacío del `fundId`**, sin verificar su existencia real. → ✅ **Cerrada en C3 (`014d101`)**: `registerPhysicalAsset` carga el `Fund`, rechaza un `fundId` inexistente (`InvalidFundReferenceException`) y un `organizationRef` distinto del del `Fund` (`CrossOrganizationAccessException`), antes de autorizar o persistir (ADR-029 §2.1).
**Pendiente (en el momento de A3):** corrección del literal `fundId` huérfano de `testD1_caminoA`. → ✅ **Cerrado**: `d30cd00` lo corrigió parcialmente y C3 (`014d101`) corrigió el intercambio `commandId`/`fundId` en todos los call sites. `testD1_caminoA_registerPhysicalAsset_inheritsFundOrganization_andEmitsSagaEnvelope` verifica ahora la organización del `Fund` y el envelope; `testD2_isolated_AssetRegisteredSagaPolicy_confirmAllocation` ejercita productor → Outbox → `OutboxSagaCoordinator` → `AssetRegisteredSagaPolicy` → `Fund`.
**Resultado (en el momento de A3):** `mvn test -pl core` ejecutado y en verde (207/207 tests en total, BUILD SUCCESS). Para la evidencia vigente, ver §12.7.
**Estado A3:** ✅ **CERRADO** (A3 `01a9f60` + `d30cd00`, C3 `014d101`; contrato en ADR-033, invariante en ADR-029).

### 12.6. Integración Real HumanActor con Identity (A6) — ✅ Cerrado
**Descripción:** Se integró formalmente la verificación de autorización real con `HumanActor` apuntando al módulo de Identity.
- **Alcance demostrado:** Existe integración real demostrada (`IdentityPrincipalPortImpl` + `OrganizationBoundaryPolicy` + `RoleAuthorizationPolicy` + `CommandService` + `Aggregate`) exclusivamente para `registerFund` a través del test `HumanActorIdentityIntegrationTest.java`. Desde C1 (`8a36894`) el test usa el cableado de producción de `TraceabilityApplication` sin mocks ni `@Import` y verifica que el bean inyectado es `IdentityPrincipalPortImpl` (`productionContext_wiresRealIdentityPrincipalPort`).
- **Cobertura adicional con `HumanActor` (en `core`, con `IdentityPrincipalPort` de test, no con Identity real):** `HumanActorAuthorizationIntegrationTest` incluye `registerPhysicalAsset`; C3 añadió el rechazo sin efectos persistidos de un `Fund` de otra organización.
- **Pendiente de E2E Completo:** No existe todavía un camino E2E HTTP/producción completo. El test de integración entra directamente al CommandService, no existe endpoint de escritura que construya HumanActor, y los comandos `confirmAllocation`, `deliverAsset`, ni el resto de `PhysicalAsset` están cubiertos por esta integración real.

### 12.7. Verificación de Estado Final
*(Corrige una cifra anterior de "407 tests" que no tenía salida literal que la respaldara.)*

Ejecución `mvn -B test` del reactor completo (8 módulos) durante C6, sobre el commit `d09cc02` (`feat/fase5-a7-1-a7-2`; el código de producción y de tests es idéntico al de `20ba931`). Resultado: `BUILD SUCCESS`, salida literal por módulo:

```
core      [INFO] Tests run: 220, Failures: 0, Errors: 0, Skipped: 0
crypto    [INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
ai        [INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
api       [INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
identity  [INFO] Tests run: 71, Failures: 0, Errors: 0, Skipped: 0
app       [INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
```

**Total: 417 tests, 0 failures, 0 errors, 0 skipped.** `contracts` y el POM padre no tienen tests.

### 12.8. Cierre del Bloque A — Remediación C1–C6

Rama: `feat/fase5-a7-1-a7-2`, sobre la base remota `842c5e3` (merge de `origin/develop`). Sin push en el momento del cierre.

| Paso | Commit | Alcance | Estado |
|---|---|---|---|
| C1 | `8a36894` | `IdentityPrincipalPort` real (`IdentityPrincipalPortImpl`) cableado en `app`; test A6 sin mocks | ✅ |
| C2 | `c07f994` | Huecos de autorización humana: `reverseAllocation` documentado como interno de la saga (ADR-036 §2) + test de compensación real | ✅ |
| C3 | `014d101` | Contrato de `registerPhysicalAsset`: validación contra el `Fund` (existencia + `organizationRef`), call sites `commandId`/`fundId` corregidos, testD1 y tests negativos | ✅ |
| C4 | `20ba931` | A7.2 endurecido: un intento por documento y ejecución, `resumeProjection` sin pérdida, ventana de 4 h y clasificación inicial cubiertas | ✅ |
| C5 | `d09cc02` | ADRs: ADR-029 y ADR-033 actualizados, ADR-042 creado | ✅ |
| C6 | *(este commit)* | Cierre documental: este documento, `golden-path.md`, `plan-correccion-fase5-e-ia.md`, `hallazgo-framework-retry-projections.md`, notas en `plan-ejecucion-agentes-fase5.md` y `fase-6-estructura-y-perimetro-convocatoria.md` | ✅ |

**Estado de los ítems del Bloque A:**
- **A1, A4, A5:** cerrados antes de C1 (ver §12.3 y `plan-correccion-fase5-e-ia.md`).
- **A2:** ✅ cerrado (§12.2.1).
- **A3:** ✅ cerrado (§12.5). Productor real de `ASSET_REGISTRATION_SAGA` con flujo verificado productor → Outbox → saga → `Fund`, e invariante de organización del Camino A.
- **A6:** ✅ cerrado (§12.6). Integración real con Identity demostrada para `registerFund`; sin camino HTTP.
- **A7.1:** ✅ **cerrado técnicamente** (`e10a3ca`). No-op idempotente para redundancia exacta, `ProcessedCommand` registrado vía `tryClaim`, familia `Redundant*` eliminada, `CommandRetryTemplate` limitado a `ConcurrencyConflictException`. La variante `04ad840` de `develop` local no es la canónica.
- **A7.2:** ✅ cerrado (`e4404e5` + `20ba931`, ADR-042).

**ADRs:**
- ADR-029: actualizado (enforcement del Camino A en `registerPhysicalAsset`, relación con ADR-034).
- ADR-033: actualizado (productor real y envelope emitido).
- ADR-042: creado (A7.2).
- ADR-038: supersedido.
- ADR-039: pendiente de resolver numéricamente.

**Deudas explícitas que permanecen abiertas:**
1. **`PhysicalAsset.deliver` (deuda de A7.1) — ✅ CERRADA en C7:** el agregado reconstruye `beneficiaryRef` desde `ASSET_DELIVERED` solo para comparar, sin tocar `custodianRef` (ADR-014). La comparación de redundancia exacta lo incluye y usa `Objects.equals`. Cubierto por `PhysicalAssetTest` (beneficiario distinto, replay, `evidenceRef` nulo) y por `ProcessedCommandIdempotencyIntegrationTest` (`deliverAsset_*`: mismo `commandId`, redundancia exacta registrada, beneficiario distinto rechazado sin efectos, `evidenceRef` nulo, transición inválida). *Texto original de la deuda:* la comparación de redundancia exacta no incluye `beneficiaryRef`, que el agregado no guarda en su estado. Una segunda entrega con otro beneficiario y los demás parámetros iguales se acepta como no-op en lugar de rechazarse. La comparación usa `equals` sin tolerar nulos en `evidenceRef`/`deliveredAt`, que `AssetDeliveredPayload` no valida. No hay test de integración de `deliverAsset` redundante con `ProcessedCommand`.
2. **A7.1 sin ADR publicado:** depende de resolver la numeración de ADR-039.
3. **Stream del hijo en Split:** `splitPhysicalAsset` solo persiste el evento del padre (sin cambios desde §11).
4. **`reverseAllocation`:** sin `authorize()` por diseño y sin restricción en runtime del tipo de actor (§12.2.1).
5. **Derivación de `organizationRef` para `ExternalActor`:** sin mecanismo (§9.6).
6. **Escritura HTTP / E2E completo con `HumanActor`:** inexistente. Pertenece a Fase 6 (ADR-037 de Fase 6).

**Decisiones humanas pendientes (no resueltas en C5/C6, sin renumeración):**
- Colisiones ADR-033 a ADR-037 entre Fase 5 y Fase 6, y el rango destino de la renumeración de Fase 6 (la propuesta 037–041 de `plan-correccion-fase5-e-ia.md` ya no es aplicable tal cual).
- Número definitivo del ADR de A7.1: reutilizar el contenido de ADR-039 con un número libre, o redactarlo de nuevo contra `e10a3ca`.
