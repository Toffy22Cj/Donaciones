# Plan de Ejecución — Fase 5: Organización, Donación en Especie y Autorización

**Proyecto:** Motor de Trazabilidad Verificable de Donaciones (`com.traceability`)
**Base de decisiones:** ADR-028 (`Organization ↔ Fund`), ADR-016 con enmienda (génesis dual de `ClearFunds`), ADR-029 (`Organization ↔ PhysicalAsset` + donación en especie), ADR-030 (`actorRef` — ubicación/persistencia), ADR-031 (taxonomía de `ActorRef`), ADR-032 (autorización de comandos: puerto Identity↔Core, guardas de pertenencia/rol, matriz) — los seis **Approved**, ver `estado-fase5.md` para el resumen ejecutivo completo y el detalle celda por celda de la matriz.
**Reglas de proceso heredadas:** `reglas-equipo-y-agentes.md` completo. Una rama por tarea, PR individual con aprobación humana, `mvn test` en verde en **los siete módulos** antes de cerrar cualquier tarea, output literal de Surefire, `git status` limpio al cierre, cero scope creep.
**Guardas de ejecución con herramientas agénticas:** las siete guardas de `plan-ejecucion-agentes-fase4.md` (Artifact/walkthrough nunca es evidencia, una tarea por entrega, verificación de Replica Set antes de Testcontainers, evidencia ensamblada desde archivo nunca de memoria, `git status` antes de `add` selectivo, prohibido `git add .`/`-A`, higiene de `target/`) **aplican sin excepción a este backlog también** — no se repiten aquí por brevedad, pero tienen la misma prioridad que en Fase 4. Se añaden dos guardas específicas de esta fase:

8. **Ningún test de `EventCanonicalMapper`/`JcsHashAdapter` ya existente puede modificarse para que un cambio de esta fase "pase".** `actorRef` (Tarea 5.0) se diseñó explícitamente para quedar **fuera** del material canonicalizado — si algún test existente empieza a fallar al tocar el adaptador de persistencia, la causa es que `actorRef` se filtró al material hasheado por error de implementación, no que el test esté desactualizado. Ver ADR-030, punto 3.
9. **La Tarea 5.4 (Camino B de donación en especie) tiene su capa de Application Service explícitamente bloqueada — no completar esa parte "para que quede listo".** ADR-031 prohíbe representar la atribución de ese comando con `null`, texto libre o un actor provisional. Cualquier intento de escribir `RegisterPhysicalAssetFromDonationService` con un actor ficticio para poder testear viola directamente esa decisión. La Tarea 5.4 entrega únicamente el soporte de dominio (Aggregate), probado con tests unitarios que invocan `PhysicalAsset.create(...)` directamente, sin pasar por ningún Application Service ni actor.

**Orden:** de lo más aislado (mecanismo de persistencia de `actorRef`, que no depende de ningún cambio de dominio) a lo más acoplado (integración de autorización en los `*CommandService` ya existentes). El bloque de autorización (5.6-5.9) depende de que `Fund.organizationRef`/`PhysicalAsset.organizationRef` existan (Tareas 5.1, 5.3) — sin eso, `OrganizationBoundaryPolicy` no tiene contra qué comparar.

---

## Bloque 1 — Mecanismo de `actorRef` (ADR-030, ADR-031)

### TAREA 5.0 — Persistencia de `actorRef` y taxonomía `ActorRef`

**Rama:** `feat/core-actorref-mechanism` | **Depende de:** nada | **ADR:** ADR-030, ADR-031

```
TAREA: Añadir actorRef al documento persistido de cada evento, sin que
participe en el material canonicalizado/hasheado, y modelar la
taxonomía ActorRef (SystemActor | ExternalActor).

ENTREGABLES:
1. ActorRef — tipo sellado (sealed interface, Java 21) en
   core.domain.event o paquete equivalente de infraestructura de
   evento: SystemActor(policyName: String), ExternalActor(sourceSystem:
   String, externalEventId: String). NO introducir HumanAccount — ADR-031
   lo difiere explícitamente.
2. Modificar MongoEventStoreAdapter (o el adaptador que persiste el
   documento del evento) para aceptar actorRef como campo adicional del
   MISMO insert atómico ya existente — sin colección nueva, sin
   escritura separada.
3. Modificar EventCanonicalMapper para que actorRef quede EXCLUIDO
   explícitamente del Map que entra a JcsHashAdapter. Esto debe quedar
   verificado con un test dedicado, no solo por omisión en el código:
   un test que construya dos eventos idénticos salvo por actorRef y
   verifique que producen el mismo eventHash.
4. Los tres constructores que hoy ensamblan un DomainEvent completo
   (o el punto único donde se ensambla el envoltorio, según ADR-030
   "fuera del dominio puro") deben aceptar actorRef como parámetro.

QUÉ NO HACER: no toques EventPayloadRegistry ni ningún schemaVersion de
payload — actorRef es metadata del documento persistido, no del payload
de negocio (ADR-030, punto 2). No introduzcas HumanAccount ni ningún
placeholder para él.

DEFINITION OF DONE: test que confirma que actorRef NO afecta eventHash
(descrito arriba). Test de round-trip: persistir un evento con
SystemActor, leerlo de vuelta, confirmar que actorRef se reconstruye
correctamente sin pasar por loadStream()/reconstitute() del Aggregate.
Output literal de Surefire de `mvn test -pl core` — módulo completo.
```

---

## Bloque 2 — `Fund` (ADR-028, ADR-016 enmienda)

### TAREA 5.1 — `Fund.organizationRef` y tratamiento de `Fund` v1

**Rama:** `feat/core-fund-organization-ref` | **Depende de:** nada (independiente de 5.0) | **ADR:** ADR-028

```
TAREA: Introducir organizationRef como estado permanente, obligatorio
e inmutable de Fund, con schemaVersion v2 y sin habilitar escritura
sobre Funds v1 preexistentes.

ENTREGABLES:
1. OrganizationRef — Value Object opaco (mismo patrón que donorRef),
   en core.domain.fund.
2. Fund.create(...) exige organizationRef como parámetro obligatorio;
   se fija en el evento génesis (sequence = 0) y queda inmutable — NO
   existe ningún método que lo modifique después.
3. FundNotAssociatedToOrganizationException — excepción de dominio,
   lanzada por Fund.execute() (no por ningún Application Service)
   cuando se invoca un comando de escritura sobre un Fund cuyo
   organizationRef es null.
4. FUND_REGISTERED:v2 / FUNDS_CLEARED:v2 — nuevo schemaVersion en
   EventPayloadRegistry con organizationRef en el payload. Upcaster
   para eventos v1 existentes: reconstruye el Fund vía reconstitute()
   con organizationRef = null — NUNCA un sentinel tipo "UNASSIGNED".
5. Fund.reconstitute() acepta organizationRef ausente sin error;
   Fund.create() lo rechaza si es null.

QUÉ NO HACER: no implementes ningún comando de reasignación de
organizationRef, ni backfill, ni migración de Funds v1 — ADR-028 los
descarta explícitamente por ausencia de necesidad de negocio. No
introduzcas ninguna interfaz compartida con PhysicalAsset para este
campo (eso es la Tarea 5.7/5.9, y aun así ADR-032/D2 lo descarta).

DEFINITION OF DONE: test unitario que confirma que create() sin
organizationRef falla en construcción (no en persistencia). Test que
confirma que un Fund reconstituido con organizationRef=null rechaza
CUALQUIER comando de escritura con FundNotAssociatedToOrganizationException
— con aserción negativa (verify(..., never())) de que el evento nunca
se llegó a persistir en ese camino. Test de upcasting de un evento v1
real. Output literal de Surefire de `mvn test -pl core`.
```

### TAREA 5.2 — Separación `ClearFundsAsGenesis` / `ClearFundsForPledge`

**Rama:** `feat/core-fund-clearfunds-split` | **Depende de:** 5.1 | **ADR:** ADR-016 (enmienda)

```
TAREA: Expresar ClearFunds como dos casos de uso de Application
distintos, produciendo un único esquema de payload FUNDS_CLEARED:v2.

ENTREGABLES:
1. ClearFundsAsGenesis(organizationRef, campaignRef, donorRef, currency,
   amount, commandId) — género directo, sequence = 0.
2. ClearFundsForPledge(fundId, amount, commandId) — transición sobre
   Fund existente, sequence > 0. NO recibe organizationRef/campaignRef/
   donorRef/currency como parámetros.
3. En FundCommandService (o el Application Service equivalente), el
   camino ForPledge carga el Fund, EXTRAE organizationRef/campaignRef/
   donorRef/currency del Aggregate reconstituido, y los incluye en el
   payload del nuevo evento — el payload resultante debe ser
   estructuralmente idéntico al de AsGenesis (mismo conjunto de
   campos, ninguno condicionado por el origen).

QUÉ NO HACER: no crees un segundo eventType. No dejes que
ClearFundsForPledge reciba ninguno de los cuatro campos como input —
si un test necesita "verificar que coinciden", es la señal de que el
comando está mal diseñado, no de que falta el test.

DEFINITION OF DONE: test que confirma que el payload de un
FUNDS_CLEARED:v2 producido por AsGenesis y otro producido por
ForPledge (sobre el mismo Fund) son estructuralmente indistinguibles
salvo por sequence. Output literal de Surefire de `mvn test -pl core`
— módulo completo, dado que se modifica un comando ya existente.
```

---

## Bloque 2bis — Capa de aplicación faltante (hallazgo de auditoría, ver `estado-fase5.md` §10)

**Contexto:** auditoría de preexistencia (`auditoria-preexistencias-fase5.md`) confirmó con evidencia literal que la capa de aplicación que conecta `Fund`/`PhysicalAsset` con el mundo exterior está mayormente sin construir — solo existen `FundCommandService.confirmAllocation`/`reverseAllocation` (los dos métodos que `AssetRegisteredSagaPolicy` ya invoca) y `PhysicalAssetCommandService.deliverAsset`. No existe génesis de `Fund`, no existe `requestAllocation`, no existe `registerPhysicalAsset`/`splitPhysicalAsset`, y de las tres `SagaPolicy` que `documento-maestro-proyecto.md` §7.1 afirma como registradas, solo `AssetRegisteredSagaPolicy` existe realmente.

**Estas cuatro tareas son prerrequisito de 5.3, 5.4 y 5.5**, que quedan pausadas (ver sus cabeceras actualizadas más abajo) hasta que este bloque cierre.

### TAREA NUEVA-1 — Comandos de génesis de `Fund`

**Rama:** `feat/core-fund-genesis-commands` | **Depende de:** nada (usa `Fund.java` ya existente, Tarea 5.1 ya cerrada) | **ADR:** ADR-016 (enmienda), ADR-028

```
TAREA: Implementar en FundCommandService los dos puntos de entrada de
aplicación para la génesis de Fund, hoy inexistentes — confirmado por
auditoría, no hay ninguna forma de crear un Fund fuera de un test
unitario que invoque el Aggregate directamente.

ENTREGABLES:
1. FundCommandService.registerFund(commandId, organizationRef,
   campaignRef, donorRef, currency, pledgedAmount, actorRef) — invoca
   Fund.create()/registerFund() del dominio (camino PLEDGED de ADR-016),
   persiste vía TransactionalEventPublisher, mismo patrón que ya usan
   confirmAllocation/reverseAllocation (constructor injection,
   CommandRetryTemplate para ConcurrencyConflictException).
2. FundCommandService.clearFundsGenesis(commandId, organizationRef,
   campaignRef, donorRef, currency, amount, actorRef) — invoca el
   camino de génesis directa de ADR-016 (sequence=0, sin promesa previa).
3. Ambos métodos reciben ActorRef como parámetro explícito (patrón ya
   establecido en la Tarea 5.0) — sin placeholders.

QUÉ NO HACER: no implementes todavía ClearFundsForPledge (eso es la
Tarea 5.2, que además depende de esta). No implementes requestAllocation
(Tarea NUEVA-2). No toques PhysicalAssetCommandService.

DEFINITION OF DONE: test de integración con Testcontainers que invoca
ambos métodos y confirma que el Fund resultante es reconstituible via
loadStream() con organizationRef correcto. Output literal de Surefire
de `mvn test -pl core`.
```

### TAREA NUEVA-2 — `requestAllocation` en `FundCommandService`

**Rama:** `feat/core-fund-request-allocation-command` | **Depende de:** NUEVA-1 | **ADR:** ADR-012

```
TAREA: Implementar el punto de entrada de aplicación que dispara
ALLOCATION_REQUESTED sobre un Fund existente — hoy inexistente,
confirmado por auditoría (Fund.requestAllocation() existe a nivel de
dominio, pero ningún método de aplicación lo invoca).

ENTREGABLES:
1. FundCommandService.requestAllocation(commandId, fundId, allocationId,
   amount, actorRef) — carga el Fund por fundId, invoca
   Fund.requestAllocation() del dominio, persiste el evento resultante.
   Mismo patrón de CommandRetryTemplate que los métodos ya existentes.

QUÉ NO HACER: no implementes la saga que reacciona a este evento (Tarea
NUEVA-4) — esta tarea solo produce el evento, no lo consume.

DEFINITION OF DONE: test de integración que registra un Fund (via
NUEVA-1), solicita una asignación, y confirma que ALLOCATION_REQUESTED
se persiste correctamente con los datos esperados. Output literal de
Surefire de `mvn test -pl core`.
```

### TAREA NUEVA-3 — Comandos de aplicación de `PhysicalAsset`

**Rama:** `feat/core-physicalasset-application-commands` | **Depende de:** nada (independiente de NUEVA-1/2, puede ejecutarse en paralelo) | **ADR:** ADR-012, ADR-005

```
TAREA: Implementar en PhysicalAssetCommandService los dos puntos de
entrada de aplicación hoy inexistentes — confirmado por auditoría, ese
servicio solo tiene deliverAsset implementado.

ENTREGABLES:
1. PhysicalAssetCommandService.registerPhysicalAsset(commandId,
   organizationRef, assetType, quantity, unitOfMeasure, custodianRef,
   currentLocation, allocationId, sourceAllocationId, actorRef) —
   invoca PhysicalAsset.register() del dominio (Camino A). NOTA: el
   parámetro organizationRef aquí SÍ es explícito a nivel de este
   comando de aplicación — es el futuro llamador (Tarea NUEVA-4, la
   saga) quien tiene la responsabilidad de resolverlo desde Fund y
   pasarlo; este comando no lo resuelve por sí mismo, solo lo recibe
   y lo delega al Aggregate.
2. PhysicalAssetCommandService.splitPhysicalAsset(commandId, assetId,
   splitQuantity, actorRef) — invoca PhysicalAsset.split() del dominio
   (ADR-005), respetando el invariante Q_after y el estado DEPLETED
   ya existentes.

QUÉ NO HACER: no implementes SplitPhysicalAssetSagaPolicy — según la
matriz de ADR-032, SPLIT_PHYSICAL_ASSET está asignado a EMPLOYEE
(humano), no a un disparador de saga; no asumas que hace falta una
saga para el split sin evidencia de qué haría. Si durante esta tarea
encuentras evidencia real de que existe una necesidad de saga aquí,
repórtala aparte — no la implementes por iniciativa propia.

DEFINITION OF DONE: tests de integración para ambos métodos, incluido
un test de split que confirma la herencia de organizationRef sobre el
Aggregate ya reconstituido. Output literal de Surefire de
`mvn test -pl core`.
```

### TAREA NUEVA-4 — `FundAllocationSagaPolicy`

**Rama:** `feat/core-fund-allocation-saga-policy` | **Depende de:** NUEVA-2, NUEVA-3 | **ADR:** ADR-012, ADR-029

```
TAREA: Implementar la SagaPolicy que reacciona a ALLOCATION_REQUESTED
y despacha el registro del PhysicalAsset correspondiente — la pieza
que el documento maestro afirmaba existente y la auditoría confirmó
ausente.

ENTREGABLES:
1. FundAllocationSagaPolicy — reacciona a ALLOCATION_REQUESTED (mismo
   patrón de SagaPolicy<T>/OutboxSagaCoordinator que ya usa
   AssetRegisteredSagaPolicy). En execute(): reconstituye el Fund por
   fundId (AllocationRequestedPayload solo trae allocationId/amount,
   confirmado por auditoría — no hay atajo posible), extrae
   organizationRef, y despacha
   PhysicalAssetCommandService.registerPhysicalAsset(...) (Tarea
   NUEVA-3) pasando ese organizationRef explícitamente.
2. En compensate(): política de compensación simétrica a la que ya usa
   AssetRegisteredSagaPolicy para su propio fallo — investigar ese
   patrón exacto antes de implementar, no inventar uno nuevo.
3. Registro formal en el mecanismo de sagas existente (mismo lugar
   donde AssetRegisteredSagaPolicy ya está registrada).

QUÉ NO HACER: no implementes ningún mecanismo de autorización aquí —
esta saga es SystemActor, fuera del alcance de P7/P9 (ADR-032/D6).

DEFINITION OF DONE: test de integración de extremo a extremo: registrar
Fund (NUEVA-1) → solicitar asignación (NUEVA-2) → confirmar que la saga
dispara el registro del PhysicalAsset con organizationRef heredado
correctamente, sin intervención manual. Test de compensación ante fallo
simulado. Output literal de Surefire de `mvn test -pl core` — módulo
completo, dado que se integra con AssetRegisteredSagaPolicy existente.
```

---

### TAREA NUEVA-5 — `HumanActor` como variante de `ActorRef` y wiring de autorización

**Rama:** `feat/core-human-actor-authorization` | **Depende de:** 5.0, 5.6, 5.7, 5.8, 5.9 | **ADR:** ADR-031, ADR-032, ADR-035

```
TAREA: Implementar `HumanActor(String accountId)` como la tercera variante de `ActorRef` y cablear los servicios de dominio (FundCommandService, PhysicalAssetCommandService) para invocar al `IdentityPrincipalPort` y validar políticas `OrganizationBoundaryPolicy` y `RoleAuthorizationPolicy` cuando el ActorRef sea HumanActor. Mantener bypass para SystemActor y ExternalActor. Actualizar los MongoDB converters.

ENTREGABLES:
1. HumanActor implementando ActorRef.
2. Inyección de IdentityPrincipalPort en los *CommandServices.
3. Wiring completo en método authorize() de los servicios.
4. Actualización de ActorRefWriteConverter y ActorRefReadConverter.
5. (Nota sobre unknown write): La rama defensiva del ActorRefWriteConverter para un tipo no reconocido se verifica por inspección del código y queda documentada como no-ejercitable mientras ActorRef mantenga exclusivamente las variantes selladas actuales. Si en el futuro aparece una nueva variante no reconocida por el converter, deberá añadirse la prueba ejecutable correspondiente antes de cerrar ese cambio.

QUÉ NO HACER: no inventar autenticación, endpoints HTTP, ni modificar Identity.
```

---

## Bloque 3 — `PhysicalAsset` (ADR-029) — ⏸ PAUSADO, depende del Bloque 2bis

### TAREA 5.3 — `PhysicalAsset.organizationRef`/`donorRef` (Camino A) y tratamiento v1

**Rama:** `feat/core-physicalasset-organization-donor-ref` | **Depende de:** NUEVA-1, NUEVA-3, NUEVA-4 (antes: "nada, independiente de 5.1/5.2" — corregido tras auditoría) | **ADR:** ADR-029

```
TAREA: Introducir organizationRef (obligatorio, heredado) y donorRef
(nullable) como estado permanente de PhysicalAsset para el Camino A
(asignación de Fund), con schemaVersion v2 y tratamiento v1 idéntico
al de Fund.

ENTREGABLES:
1. PhysicalAsset.organizationRef — obligatorio, inmutable, heredado
   automáticamente vía AssetRegisteredSagaPolicy desde Fund.organizationRef.
   RegisterPhysicalAsset (comando existente, Camino A) NO recibe
   organizationRef como parámetro de entrada.
2. PhysicalAsset.donorRef — nullable. Camino A: SIEMPRE null (el
   donante financiero no se hereda como donante físico — distinción
   semántica de ADR-029, no técnica).
3. ASSET_REGISTERED:v2 en EventPayloadRegistry — organizationRef y
   donorRef en el payload (donorRef puede ser null). allocationId/
   sourceAllocationId conservan su comportamiento ya existente (ADR-005/012).
4. Upcaster de ASSET_REGISTERED:v1 → reconstitute() con
   organizationRef=null, donorRef=null. Sin backfill, sin UNASSIGNED.
5. Comandos de escritura sobre un PhysicalAsset con organizationRef=null
   se rechazan permanentemente (mismo patrón que Fund v1 — reutiliza o
   replica FundNotAssociatedToOrganizationException con su equivalente
   para PhysicalAsset, nombrada explícitamente, no genérica).

QUÉ NO HACER: no implementes todavía el Camino B (Tarea 5.4). No
introduzcas ninguna interfaz compartida con Fund para organizationRef.

DEFINITION OF DONE: test que confirma herencia correcta de
organizationRef vía la saga existente. Test que confirma donorRef=null
en el camino A. Test de rechazo permanente sobre PhysicalAsset v1.
Output literal de Surefire de `mvn test -pl core`.
```

### TAREA 5.4 — Camino B: soporte de dominio para donación en especie (Application Service BLOQUEADO)

**Rama:** `feat/core-physicalasset-donation-genesis-domain` | **Depende de:** 5.3 (transitivamente pausada hasta Bloque 2bis) | **ADR:** ADR-029, ADR-031

```
TAREA: Implementar EXCLUSIVAMENTE el soporte de Aggregate para la
génesis directa de PhysicalAsset por donación en especie. La capa de
Application Service queda fuera de esta tarea — ver guarda de
ejecución #9 al inicio de este documento.

ENTREGABLES:
1. PhysicalAsset.create(...) — sobrecarga o factory adicional para
   génesis directa: organizationRef y donorRef como parámetros
   obligatorios de entrada (a diferencia del Camino A, aquí NO se
   heredan porque no hay Fund de origen). donationRef como parámetro
   adicional, nullable a nivel de tipo pero SIEMPRE presente cuando el
   origen es este camino (ver punto 3).
2. ASSET_REGISTERED:v2 (mismo schemaVersion de la Tarea 5.3) admite
   donationRef en el payload — presente y no-null en Camino B, null en
   Camino A. Verificar que esto NO introduce una segunda forma del
   mismo eventType (mismo esquema estructural, valores distintos según
   génesis — patrón ya usado en FUNDS_CLEARED:v2).
3. NO implementar en esta tarea: la generación de donationRef
   server-side (eso vive en el futuro Application Service), ningún
   comando RegisterPhysicalAssetFromDonation, ningún CommandType nuevo
   en la matriz de autorización — la celda ya existe en ADR-032/D5,
   pero no hay código que la consuma todavía.
4. Herencia en ASSET_SPLIT: si el Aggregate de split ya existe (ver
   Tarea 5.5, que puede ejecutarse en paralelo o inmediatamente
   después), confirmar que un PhysicalAsset nacido por Camino B
   propaga donorRef/donationRef a sus hijos igual que organizationRef.

QUÉ NO HACER: no escribas ningún Application Service, controller, ni
test que invoque este camino a través de un actor — todos los tests de
esta tarea llaman directamente a PhysicalAsset.create(...) desde el
test unitario, sin pasar por RoleAuthorizationPolicy ni
OrganizationBoundaryPolicy. No inventes un HumanAccount de prueba "solo
para testear" — eso es exactamente lo que ADR-031 prohíbe.

DEFINITION OF DONE: tests unitarios de invariantes del Aggregate
(organizationRef/donorRef obligatorios, donationRef presente) llamando
directamente al Aggregate. Test que confirma que un evento
ASSET_REGISTERED:v2 de Camino A y otro de Camino B comparten esquema
estructural. Output literal de Surefire de `mvn test -pl core`. El PR
de esta tarea debe declarar explícitamente en su descripción: "Esta
tarea NO habilita la ejecución de donación en especie — la capa de
Application Service permanece bloqueada por ADR-031 hasta que exista
HumanAccount."
```

### TAREA 5.5 — Herencia en `ASSET_SPLIT`

**Rama:** `feat/core-physicalasset-split-inheritance` | **Depende de:** 5.3 (transitivamente pausada hasta Bloque 2bis) | **ADR:** ADR-029

```
TAREA: SplitPhysicalAsset hereda organizationRef, donorRef y
donationRef del padre a todo hijo, sin excepción, sin campo `source*`
paralelo.

ENTREGABLES:
1. Modificar la lógica de ASSET_SPLIT para que cada PhysicalAsset hijo
   reciba organizationRef/donorRef/donationRef IDÉNTICOS al padre.
   SplitPhysicalAsset (el comando) NO recibe ninguno de los tres como
   parámetro de entrada — el Aggregate los toma del propio padre.
2. Confirmar que un hijo de un padre nacido por Camino A (donorRef=null,
   donationRef=null) hereda esos nulls correctamente — por
   transitividad, ningún split puede "adquirir" un donante que el
   padre nunca tuvo.

QUÉ NO HACER: no introduzcas sourceOrganizationRef/sourceDonorRef/
sourceDonationRef — ADR-029 descarta explícitamente ese patrón para
estos tres campos (a diferencia de allocationId/sourceAllocationId).

DEFINITION OF DONE: test de split de un asset Camino A (verifica
nulls heredados) y de un asset Camino B (si la Tarea 5.4 ya está
mergeada; si no, dejar el test marcado y completarlo cuando 5.4 cierre).
Output literal de Surefire de `mvn test -pl core`.
```

---

## Bloque 4 — Autorización (ADR-032)

### TAREA 5.6 — Puerto `Identity ↔ Core` (P11)

**Rama:** `feat/contracts-identity-principal-port` | **Depende de:** nada (independiente del Bloque 2/3, pero conviene ejecutarla después para evitar revisar dos PRs grandes en paralelo) | **ADR:** ADR-032/D1

```
TAREA: Definir el puerto de resolución de identidad para autorización,
en contracts, e implementarlo en identity.

ENTREGABLES:
1. contracts: IdentityPrincipalPort (interfaz) + AuthorizationPrincipal
   (DTO: accountId: String, organizationId: String?, roles:
   Set<AuthorizationRole>) + AuthorizationRole (enum propio del
   contrato: ADMINISTRATOR, REPRESENTATIVE, EMPLOYEE — NO es
   identity.domain.Role, es un tipo distinto).
2. identity/pom.xml: nueva dependencia a contracts (arista antes
   evitada por "sin necesidad real", ADR-027 — ahora justificada).
   Verificar con el test de ArchUnit ya existente del módulo que esto
   no introduce ninguna dependencia identity → core.
3. identity: IdentityPrincipalPortImpl — compone
   AccountRepositoryPort + OrganizationRepositoryPort (ya existentes)
   para resolver accountId → organizationId → roles de la Membership.
4. Mapeo identity.domain.Role → AuthorizationRole: switch expression
   EXHAUSTIVO, SIN default. Debe fallar en compilación (no en
   runtime) si identity.domain.Role gana un valor nuevo sin clasificar.
5. Test unitario de corrección semántica del mapeo (ADMINISTRATOR →
   ADMINISTRATOR, no un typo) — separado de la exhaustividad, que
   garantiza el compilador.

QUÉ NO HACER: no expongas Account, Organization ni Membership como
tipos a través del puerto — solo el DTO plano. No introduzcas
Value Objects contractuales para accountId/organizationId — permanecen
String (ADR-032/D1, identificadores opacos que se transportan sin
reinterpretación).

DEFINITION OF DONE: test que confirma que agregar un valor no
clasificado a un Role de prueba (o revisar el switch manualmente) deja
de compilar. Test de resolución end-to-end con Testcontainers: Account
con Membership real → AuthorizationPrincipal correcto. Output literal
de Surefire de `mvn test -pl identity` y de `mvn test -pl contracts`.
```

### TAREA 5.7 — `OrganizationBoundaryPolicy` (P9)

**Rama:** `feat/core-organization-boundary-policy` | **Depende de:** 5.1, 5.3, 5.6 | **ADR:** ADR-032/D2

```
TAREA: Implementar la guarda de pertenencia organizacional, stateless,
sin conocer ningún Aggregate concreto.

ENTREGABLES:
1. OrganizationBoundaryPolicy en core.application.authorization —
   único método: assertBelongs(principalOrganizationId: String?,
   resourceOrganizationRef: String). Sin dependencias de
   infraestructura, sin repositorios.
2. CrossOrganizationAccessException — misma excepción para los tres
   casos: principalOrganizationId null, mismatch, o (por completitud,
   aunque no debería ocurrir) resourceOrganizationRef null.
3. NO introducir ninguna interfaz HasOrganizationRef ni cualquier
   abstracción compartida entre Fund y PhysicalAsset — cada
   *CommandService sigue extrayendo organizationRef directamente del
   Aggregate que ya cargó (eso es la Tarea 5.9, no esta).

QUÉ NO HACER: no le des a esta clase conocimiento de Fund,
PhysicalAsset, Account ni Membership. No la hagas @Component con
estado — debe ser trivialmente reutilizable sin inyección de
dependencias más allá de nada.

DEFINITION OF DONE: tests unitarios puros (sin Spring context) de los
tres casos: null/null, valor/valor distinto, valor/valor igual (no
lanza). Output literal de Surefire de `mvn test -pl core`.
```

### TAREA 5.8 — `CommandType`, `RoleAuthorizationPolicy` y matriz (P7)

**Rama:** `feat/core-role-authorization-policy` | **Depende de:** 5.6 | **ADR:** ADR-032/D4, D5

```
TAREA: Implementar el mecanismo de autorización por rol y cargar la
matriz de seis celdas ya decidida (ADR-032/D5).

ENTREGABLES:
1. CommandType — enum cerrado en core.application.authorization:
   REGISTER_FUND, CLEAR_FUNDS_AS_GENESIS, CLEAR_FUNDS_FOR_PLEDGE,
   REGISTER_PHYSICAL_ASSET, REGISTER_PHYSICAL_ASSET_FROM_DONATION,
   SPLIT_PHYSICAL_ASSET.
2. RoleAuthorizationPolicy.authorize(principal: AuthorizationPrincipal,
   commandType: CommandType) — switch expression EXHAUSTIVO, SIN
   default, sobre CommandType. Valores según la matriz:
   REGISTER_FUND → {ADMINISTRATOR}
   CLEAR_FUNDS_AS_GENESIS → {ADMINISTRATOR}
   CLEAR_FUNDS_FOR_PLEDGE → {ADMINISTRATOR}
   REGISTER_PHYSICAL_ASSET → {EMPLOYEE}
   REGISTER_PHYSICAL_ASSET_FROM_DONATION → {EMPLOYEE}
   SPLIT_PHYSICAL_ASSET → {EMPLOYEE}
3. Excepción de autorización por rol — nombrada explícitamente (ej.
   InsufficientRoleException), distinta de CrossOrganizationAccessException.
4. Regla de ArchUnit: todo *CommandService en core.application debe
   referenciar RoleAuthorizationPolicy en su implementación — verifica
   integración estructural, NO orden de invocación (eso es un test de
   comportamiento, Tarea 5.9).

QUÉ NO HACER: no implementes CommandDispatcher — ADR-032/D4 lo difiere
explícitamente. No le asignes ningún rol a
REGISTER_PHYSICAL_ASSET_FROM_DONATION más allá de lo ya decidido, y no
elimines esa celda de la matriz aunque su Application Service esté
bloqueado (Tarea 5.4) — el mecanismo debe estar listo para cuando
HumanAccount exista.

DEFINITION OF DONE: test que confirma que RoleAuthorizationPolicy
autoriza/rechaza correctamente cada una de las seis celdas, con
aserciones negativas (verify(..., never())) de que un principal con un
rol no autorizado nunca invoca el Aggregate subyacente en el flujo
completo cuando se prueba junto con la Tarea 5.9. Resultado de la
regla de ArchUnit ejecutándose en verde contra el código real. Output
literal de Surefire de `mvn test -pl core`.
```

### TAREA 5.9 — Composición e integración en `*CommandService` (P10)

**Rama:** `feat/core-authorization-wiring` | **Depende de:** 5.7, 5.8 | **ADR:** ADR-032/D3

```
TAREA: Integrar el mecanismo de bypass de SystemActor y ExternalActor
en los Application Services ya existentes que tienen contraparte
en la matriz (FundCommandService, PhysicalAssetCommandService o equivalentes).

ENTREGABLES:
1. Cada método de *CommandService correspondiente a uno de los cinco
   CommandType implementables hoy (todo salvo
   REGISTER_PHYSICAL_ASSET_FROM_DONATION) implementa un switch
   exhaustivo sobre ActorRef.
2. Confirmar (§9.6, ADR-032/D6) que los caminos disparados por
   SagaPolicy (AssetRegisteredSagaPolicy) NO pasan por esta guarda —
   el actor en esos casos es SystemActor, fuera del alcance de P7/P9
   por diseño. Verificar explícitamente que el wiring no se aplicó por
   error a esos caminos internos.

QUÉ NO HACER: no inventar HumanAccount ni accountId falsos.
No llamar a IdentityPrincipalPort porque no existe una fuente real de accountId hoy.

NOTA ARQUITECTÓNICA: Con el modelo ActorRef vigente, SystemActor y ExternalActor
omiten P7/P9 por diseño. HumanAccount fue diferido por ADR-031. Por tanto,
la secuencia Boundary -> Role -> Aggregate no tiene actualmente un camino E2E
ejecutable dentro de CommandService. La incorporación futura de HumanAccount
deberá implementar y probar dicha secuencia.

DEFINITION OF DONE: test que confirma que un comando disparado por
SagaPolicy (SystemActor) o ExternalActor continúa normalmente (bypass)
y que el switch sobre ActorRef es exhaustivo en los comandos actuales.
Output literal de Surefire de `mvn test -pl core` — módulo completo.
```

---

## Bloque 5 — Verificación end-to-end

### TAREA 5.10 — Suite de integración completa de Fase 5

**Rama:** `feat/fase5-integration-tests` | **Depende de:** 5.0, 5.2, 5.4, 5.5, 5.9

```
TAREA: Cerrar Fase 5 con una suite de integración que ejercite el
dominio y la autorización juntos, contra Testcontainers real.

ENTREGABLES: Incluir escenarios de negocio completos de extremo a extremo ejecutados por los ActorRef disponibles hoy (SystemActor, ExternalActor), cubriendo:

- registro de Fund;
- ClearFundsAsGenesis;
- ClearFundsForPledge;
- asignación con generación de PhysicalAsset por Camino A con organizationRef heredado y donorRef null;
- SplitPhysicalAsset con genealogía heredada.

En cada escenario debe verificarse explícitamente que OrganizationBoundaryPolicy (P9) y RoleAuthorizationPolicy (P7) son bypasseadas para SystemActor/ExternalActor mediante aserciones negativas (verify(..., never())), consistente con ADR-032/D6.

Los escenarios de rechazo por rol insuficiente (InsufficientRoleException) y por límite organizacional (CrossOrganizationAccessException) dependen de HumanAccount, que ADR-031 difiere explícitamente y prohíbe sustituir con un actor provisional, accountId ficticio o mecanismo equivalente, incluso en contexto de test. Por tanto, estos escenarios quedan fuera del alcance de 5.10 y pendientes hasta que HumanAccount exista. Esto no es un defecto de la suite, sino el estado arquitectónico esperado, igual que REGISTER_PHYSICAL_ASSET_FROM_DONATION.

DEFINITION OF DONE: reactor completo (7 módulos) en verde. Output
literal de Surefire de `mvn clean test` desde la raíz. `git status`
limpio. Confirmación explícita en el PR de que
REGISTER_PHYSICAL_ASSET_FROM_DONATION permanece sin ruta de ejecución
end-to-end (solo tests unitarios de dominio, Tarea 5.4) — esto no es
un defecto de la suite, es el estado esperado hasta que exista
HumanAccount. Resumen de decisiones de implementación tomadas durante
el backlog entregado para revisión humana.
```

---

## Resumen de ramas

| Tarea | Rama | Depende de |
|---|---|---|
| 5.0 | feat/core-actorref-mechanism | — |
| 5.1 | feat/core-fund-organization-ref | — |
| 5.2 | feat/core-fund-clearfunds-split | 5.1 |
| **NUEVA-1** | **feat/core-fund-genesis-commands** | COMPLETADA |
| **NUEVA-2** | **feat/core-fund-request-allocation-command** | COMPLETADA |
| **NUEVA-3** | **feat/core-physicalasset-application-commands** | COMPLETADA |
| **NUEVA-4 / 4B** | **feat/core-pending-allocation-read-model / core-nueva-4b-reverse-allocation** | COMPLETADA |
| **NUEVA-5** | **feat/core-human-actor-authorization** | COMPLETADA |
| 5.3 | feat/core-physicalasset-organization-donor-ref | COMPLETADA |
| 5.4 | feat/core-physicalasset-donation-genesis-domain / in-kind-donation | COMPLETADA |
| 5.5 | feat/core-physicalasset-split-inheritance | COMPLETADA |
| 5.6 | feat/contracts-identity-principal-port | COMPLETADA |
| 5.7 | feat/core-organization-boundary-policy | COMPLETADA |
| 5.8 | feat/core-role-authorization-policy | COMPLETADA |
| 5.9 | feat/core-authorization-wiring | COMPLETADA |
| 5.10 | feat/fase5-integration-tests | COMPLETADA |
| 5.11 | feat/fase5-cierre-documental | COMPLETADA |

**Nota:** NUEVA-1 y NUEVA-3 no dependen entre sí y pueden ejecutarse en paralelo. NUEVA-2 depende solo de NUEVA-1. NUEVA-4 es el punto de convergencia — depende de NUEVA-2 y NUEVA-3 juntas.

**Nota sobre paralelización:** 5.0, 5.1/5.2, 5.3/5.4/5.5, y 5.6 no tienen dependencias cruzadas entre sí y podrían ejecutarse en ramas paralelas por distintos agentes/personas — pero `reglas-equipo-y-agentes.md` §3.4 exige que, si más de un agente trabaja en la misma tarea o en tareas que tocan el mismo módulo (`core` en este caso: 5.0, 5.1-5.5 y 5.7-5.9 todas lo tocan), no se solape trabajo sin que el punto de control anterior haya cerrado explícitamente. Recomendación: no paralelizar dentro de `core` sin coordinación explícita del equipo humano, aunque el grafo de dependencias formal lo permitiría.

---

## Cierre de Fase 5

Concluida la Tarea 5.11, la Fase 5 queda formalmente cerrada.

- **Trabajo terminado:** Todas las tareas 5.0-5.11, además de NUEVA-1 a NUEVA-5 (incluyendo NUEVA-4B) están completamente integradas, probadas y documentadas en `develop`.
- **Deudas técnicas diferidas:**
  - La orquestación completa E2E de la creación del stream del agregado hijo al ejecutar Split.
  - Generación de `OutboxMessage` para el Camino A en `registerPhysicalAsset`.
  - Derivación real del `organizationRef` para `ExternalActor` en los pagos externos.
- **Trabajo perteneciente a fases posteriores:** Autenticación, endpoints HTTP, despliegues, integración directa con Identity externa/bases de datos humanas.
No quedan tareas de implementación pendientes para Fase 5.
