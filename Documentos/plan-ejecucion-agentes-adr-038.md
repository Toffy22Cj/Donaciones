# Plan de ejecución para agentes — ADR-038 (registro de trabajo)

> **Documento histórico.** Es el plan y el registro de decisiones con el que se ejecutó ADR-038 entre el 2026-09-30 y el 2026-10-04, tal como se mantuvo durante el trabajo. Las secciones de cada tarea reflejan el plan **previo** a implementarla; lo que se decidió después está en los apartados "Decisiones del debate…" y en el registro final. El resumen oficial y vigente está en **ADR-038 §9** y en **estado-fase6.md §7**; si algo de aquí lo contradice, prevalecen esos documentos.


**Alcance:** únicamente ADR-038 y la enmienda de ADR-026. Nada de ADR-037/039/040/041.
**Base verificada:** `develop` en `673eda9` (2026-09-28). Todos los nombres de clase, paquete y archivo citados abajo existen en ese commit.
**Agente de implementación:** Antigravity. **Revisor:** humano del equipo + Claude/ChatGPT en modo revisión.

---

## Reglas comunes a TODAS las tareas (pegar al inicio de cada prompt)

```
CONTEXTO OBLIGATORIO: lee antes de escribir código:
- Documentos/reglas-equipo-y-agentes.md (completo)
- Documentos/ADR-038-identidad-platform-administrator-verificacion-organization.md
- Documentos/ADR-026-modelo-dominio-identidad.md y
  Documentos/ADR-026-enmienda-desactivacion-platform-administrator.md
- Documentos/verificacion-adr-038.md (§2 hallazgos y §6 tests exigidos)

REGLAS:
1. Implementa SOLO lo que dice esta tarea. Si falta algo o algo contradice el ADR:
   DETENTE y repórtalo (qué ADR, qué sección, qué caso). No lo resuelvas por tu cuenta.
2. Presenta implementation_plan.md y ESPERA aprobación humana antes de escribir código.
3. Cada clase pública nueva debe poder señalarse contra una sección concreta de ADR-038
   o de la enmienda. Indícala en el comentario del PR, no en el código.
4. Toda condición de fallo usa su excepción nombrada, en identity.domain.exception
   (patrón existente: el módulo no tiene excepciones de capa de aplicación).
5. Tests de integración: extender BaseMongoIntegrationTest (Testcontainers, mongo:6.0,
   replica set). Para forzar conflictos de escritura REALES reutiliza la técnica de
   OrganizationApplicationServiceIntegrationTest (SpyBean sobre el repositorio +
   CyclicBarrier tras la lectura). No simules el conflicto con mocks.
6. Aserciones negativas obligatorias cuando haya ramas excluyentes: verifica que el
   documento no cambió, que el contador no cambió y que NO se escribió auditoría.
7. "Tests pasando" = salida LITERAL de Surefire (Tests run / Failures / Errors) del
   módulo completo. Nunca -DskipTests. Nunca "BUILD SUCCESS" de un install sin tests.
8. Si Docker/Testcontainers falla, diagnostica la causa comparando con un módulo
   hermano que funcione. No lo reportes como "pendiente del usuario" sin intentarlo.
9. No crees documentos, ADRs ni "siguientes fases" que esta tarea no pida.
10. La documentación de estado se actualiza SOLO con el resultado real de esta sesión.
```

---

## Orden y dependencias

Cada rama sale de `develop` **después** de que la anterior esté fusionada (evita conflictos: varias tocan los mismos servicios).

| # | Rama | Qué | Depende de | Bloqueo |
|---|---|---|---|---|
| 0 | `chore/docs-adr-038-cierre` | Documentos (parche ya preparado) | — | La hace el humano, no Antigravity |
| 1 | `fix/identity-resolve-principal-inactive` | H3 | 0 | — |
| 2 | `fix/identity-deactivate-retry` | H6 | 0 | — |
| 3 | `feat/identity-retry-policy` | ADR-038 §2.8 | 2 | **Decisión D1** |
| 4 | `fix/identity-audit-actor` | H4/H5, ADR-038 §2.2 | 2 | — |
| 5 | `feat/contracts-principal-platform-authority` | `platformAuthority` en el principal | 1 | — |
| 6 | `feat/identity-platform-authority` | Grant/Revoke + singleton + guarda de la enmienda | 3, 4, 5 | — |
| 7 | `feat/identity-platform-bootstrap` | Bootstrap | 6 | — |
| 8 | `feat/identity-organization-verification` | Verificación de Organization | 4, 5 | **Decisión D2** |

**Fuera de este plan, a propósito:** login y JWT (`AuthenticateAccountPort`, `TokenIssuerPort`, filtro en `api`). Requieren elegir una librería JWT, que es una dependencia nueva y necesita su propio ADR antes de cualquier código (reglas §3.5).

---

## Decisiones pendientes que bloquean tareas concretas

**D1 — Reintentar solo el commit (bloquea la tarea 3).** ADR-038 §2.8 dice: ante `UnknownTransactionCommitResult`, reintentar solo el commit. En spring-data-mongodb 4.4.4 eso se hace sobrescribiendo `MongoTransactionManager.doCommit`. Pero el `MongoTransactionManager` de producción es **uno solo**, en `app` (`TraceabilityInfrastructureConfig`), y lo comparte `core`. Cambiarlo altera el commit de **todo el sistema**, no solo de `identity`.
- **Recomendación:** no tocar el gestor compartido. El driver 5.2.1 ya reintenta el commit **una vez** por sí mismo (es una escritura reintentable, verificado en `CommitTransactionOperation`). Si aun así llega `UnknownTransactionCommitResult`, lanzar `PlatformAuthorityOutcomeUnknownException` sin más reintentos. Requiere ajustar la redacción de ADR-038 §2.8.
- Alternativa: sobrescribir `doCommit` en el gestor de `app`, con un ADR propio porque afecta a `core`.

**D2 — `verificationStatus` de organizaciones existentes (bloquea la tarea 8).** Los documentos `Organization` actuales no tienen ese campo, y ADR-038 no dice qué estado reciben al leerse.
- **Recomendación:** documento sin campo → `PENDING_VERIFICATION`. Es el valor seguro (fail-closed): nadie queda verificado sin que un Platform Administrator lo decida. Sin reescribir documentos. Impacto real bajo: ningún código de producción crea organizaciones hoy.
- Alternativa: tratarlas como `VERIFIED`, que es más cómodo pero verifica sin revisión.
- **DECIDIDA por el humano (2026-10-02): `PENDING_VERIFICATION`** (recomendación).

---

## TAREA 1 — `fix/identity-resolve-principal-inactive` (H3)

```
TAREA: IdentityPrincipalPortImpl.resolvePrincipal debe rechazar cuentas INACTIVE con
InactiveAccountException (ADR-038 §2.7). Hoy devuelve un AuthorizationPrincipal completo.

ORDEN OBLIGATORIO:
1. Primero, test de regresión en IdentityPrincipalPortImplTest: cuenta INACTIVE (con y
   sin organización) → espera InactiveAccountException. Ejecútalo y MUESTRA que FALLA
   con el código actual (salida literal de Surefire).
2. Después, la corrección mínima en resolvePrincipal: comprobar status antes de
   construir el principal.
3. Después, mvn test -pl identity completo.

QUÉ NO HACER: no cambies la forma de AuthorizationPrincipal (eso es la tarea 5). No toques
el comportamiento para cuentas ACTIVE sin organización (sigue devolviendo roles vacíos;
cambiarlo es otra decisión). No añadas platformAuthority.

DEFINITION OF DONE:
- Salida de Surefire mostrando el test NUEVO en rojo antes de la corrección.
- Salida de Surefire de mvn test -pl identity en verde después.
- Aserción negativa: para INACTIVE, nunca se consulta OrganizationRepositoryPort.
```

## TAREA 2 — `fix/identity-deactivate-retry` (H6)

```
TAREA: los 7 servicios de identity que usan @Transactional sin reintento deben usar
MongoTransactionRetryHelper, como exigen ADR-026 (§Concurrencia) y estado-fase4.md:
AssignAdministratorService, RemoveEmployeeService, ReactivateAccountService,
ChangeCredentialsService, CreateAccountService, RemoveAdministratorService,
DeactivateAccountService.

ORDEN OBLIGATORIO:
1. Primero, test de regresión en AccountApplicationServiceIntegrationTest: dos
   DeactivateAccount/ReactivateAccount concurrentes sobre la MISMA cuenta, con la
   técnica SpyBean + CyclicBarrier. Con el código actual, uno de los dos debe fallar
   con un error de conflicto crudo. MUESTRA la salida en rojo.
2. Después, migrar los 7 servicios a executeWithRetry, eliminando @Transactional.
3. Después, mvn test -pl identity completo.

QUÉ NO HACER: no cambies MongoTransactionRetryHelper (backoff, excepción nueva: tarea 3).
No cambies la lógica de negocio de ningún servicio. No cambies firmas públicas.

DEFINITION OF DONE:
- Rojo antes / verde después, salidas literales.
- El test comprueba que ambos llamadores terminan sin excepción y que el retryCount
  del helper es > 0 (evidencia de que el reintento ocurrió de verdad).
- Test de arquitectura o grep documentado en el PR: ningún servicio de identity queda
  con @Transactional.
```

## TAREA 3 — `feat/identity-retry-policy` (ADR-038 §2.8) — **NO INICIAR sin la decisión D1**

```
TAREA: endurecer MongoTransactionRetryHelper.
1. Máximo 3 intentos (ya es así; queda como constante, no configurable).
2. Espera entre intentos: backoff exponencial con jitter. Base, factor y tope como
   constantes explícitas en el helper, con un comentario que cite ADR-038 §2.8.
3. Intentos agotados por TransientTransactionError → ConcurrentModificationException
   (nueva, identity.domain.exception), con la MongoException original como causa.
4. Excepción con etiqueta UnknownTransactionCommitResult en la cadena de causas →
   PlatformAuthorityOutcomeUnknownException (nombre sujeto a la redacción final de D1).
   NUNCA se re-ejecuta la operación de negocio en este caso.
5. Cualquier otra excepción (incluidas las de dominio) → se propaga sin reintentar.

TESTS:
- Unitario del helper con una operación simulada: transitorio ×1 → éxito al 2º intento;
  transitorio ×3 → ConcurrentModificationException; Unknown → OutcomeUnknown con la
  operación invocada EXACTAMENTE una vez (verify(times(1))); excepción de dominio →
  una sola invocación, sin reintento.
- El test de backoff no depende del reloj real (inyecta la estrategia de espera o un
  Sleeper; no Thread.sleep en el test).

QUÉ NO HACER: no toques el MongoTransactionManager de app ni el de IdentityTestApplication
salvo que D1 elija la alternativa (y entonces con su ADR). No añadas configuración
externa para los parámetros del backoff.

DEFINITION OF DONE: mvn test -pl identity en verde, salida literal.
```

## TAREA 4 — `fix/identity-audit-actor` (H4/H5, ADR-038 §2.2)

```
TAREA: el Audit Log de identity debe registrar el actor REAL.

ENTREGABLES:
1. Tipo de actor propio de identity (identity.domain.model), cerrado, con dos variantes:
   cuenta (accountId) y sistema (identificador de proceso). NO importar nada de
   com.traceability.core.. (añade una regla ArchUnit que lo impida si no existe).
2. AuditLogEntry usa ese tipo en vez de AccountId actorAccountId.
3. Todo Application Service que escribe auditoría recibe el actor como parámetro
   OBLIGATORIO. Ninguno lo deduce del objetivo. (No hay llamadores de producción:
   solo se actualizan los tests.)
4. Persistencia (AuditLogEntryDocument/AuditLogEntryMapper): el documento nuevo guarda
   el actor en un campo NUEVO. El campo actorAccountId deja de escribirse.
5. Lectura compatible: un documento que solo tiene actorAccountId (forma antigua) se lee
   como régimen PRE_CORTE; uno con el campo nuevo, como POST_CORTE. El dominio expone
   el régimen explícitamente.

ORDEN OBLIGATORIO:
1. Test de regresión primero: ejecutar DeactivateAccount indicando un actor distinto del
   objetivo y comprobar que la auditoría registra ese actor. Con el código actual no
   compila o registra el objetivo: MUESTRA el fallo.
2. Implementación.

TESTS EXIGIDOS POR ADR-038 §2.2 (los cuatro, obligatorios):
- documento con forma antigua → PRE_CORTE;
- documento con forma nueva → POST_CORTE;
- un documento antiguo NUNCA se interpreta como actor contractual (aserción negativa);
- el código nuevo NUNCA escribe un documento con la forma antigua (inspección del
  documento crudo en Mongo tras escribir).

QUÉ NO HACER: no migres ni reescribas documentos existentes (ADR-025: append-only).
No cambies AuditAction salvo lo que pida esta tarea (ninguno).

DEFINITION OF DONE: mvn test -pl identity en verde, salida literal. Anotar en el PR el
commit de merge: es el dato de "fecha de corte" que ADR-038 §2.2 debe registrar.
```

## TAREA 5 — `feat/contracts-principal-platform-authority` (ADR-038 §2.7)

```
TAREA: añadir platformAuthority al principal de autorización.

ENTREGABLES:
1. contracts.authorization.PlatformAuthority (enum neutral: ADMINISTRATOR).
2. AuthorizationPrincipal gana el componente platformAuthority (nullable).
3. identity: su propio enum de dominio para Account.platformAuthority, y el mapeo al de
   contracts en IdentityPrincipalPortImpl, con el mismo patrón que mapRole (switch
   exhaustivo, sin default).
4. Actualizar TODOS los llamadores del constructor del record (hoy 15 en tests de core, contados en develop 673eda9,
   y los de identity). Sin constructor de compatibilidad de 3 argumentos: cada llamador
   declara explícitamente su platformAuthority.

NOTA: Account.platformAuthority y su persistencia (AccountDocument, AccountMapper)
pertenecen a la TAREA 6. Aquí resolvePrincipal devuelve siempre null en ese campo; se
documenta en el PR.

QUÉ NO HACER: no añadas lógica de Platform Administrator. No cambies RoleAuthorizationPolicy
ni OrganizationBoundaryPolicy.

DEFINITION OF DONE: es un cambio de contrato consumido por otros módulos (reglas §3.5).
Salida literal de Surefire de mvn test en contracts, core, identity y app (el reactor
completo desde la raíz es preferible).
```

## TAREA 6 — `feat/identity-platform-authority` (ADR-038 §2.3, enmienda ADR-026)

```
TAREA: Platform Administrator: estado, GRANT, REVOKE y guarda de desactivación.

ENTREGABLES:
1. Account.platformAuthority (nullable) + persistencia en AccountDocument/AccountMapper
   (documento sin campo → null). resolvePrincipal lo expone (completa la tarea 5).
2. Invariante en Account (enmienda ADR-026): deactivate() sobre cuenta con
   platformAuthority → PlatformAdministratorDeactivationException.
3. PlatformAuthorityState: documento singleton _id="platform-authority",
   {activeAdministratorCount, version}; version solo diagnóstico.
4. PlatformAuthorizationPolicy (identity.application.authorization) y PlatformCommandType
   (5 valores, switch exhaustivo sin default).
5. GrantPlatformAuthority / RevokePlatformAuthority con EXACTAMENTE el orden de
   precondiciones y excepciones de ADR-038 §2.3.2, todo dentro de UNA transacción vía
   el helper. Escrituras condicionales sobre Account (defensa en profundidad) y
   modificación del singleton en todo éxito.
6. Auditoría con el actor de la tarea 4. Acciones nuevas en AuditAction:
   PLATFORM_AUTHORITY_GRANTED, PLATFORM_AUTHORITY_REVOKED.

TESTS (verificacion-adr-038.md §6):
- Cada rama de error de §2.3.2 con aserciones negativas (cuenta, contador y auditoría
  intactos).
- Concurrencia real (SpyBean + CyclicBarrier):
  a) REVOKE(A) || REVOKE(B) con 2 admins → exactamente uno LastPlatformAdministrator;
  b) GRANT(C) || GRANT(C) → exactamente uno AlreadyGranted, contador +1;
  c) GRANT(C) || REVOKE(C);
  d) GRANT(C) || DeactivateAccount(C) → ninguna cuenta con platformAuthority INACTIVE.
  Tras cada escenario: activeAdministratorCount == nº de cuentas con platformAuthority,
  y una entrada de auditoría por transición efectiva.
- Estado ausente → PlatformAuthorityStateMissingException en GRANT y REVOKE.

QUÉ NO HACER: nada de bootstrap (tarea 7) ni de verificación de Organization (tarea 8).
Sin auto-reparación del singleton.

DEFINITION OF DONE: mvn test -pl identity en verde, salida literal; los cuatro escenarios
de concurrencia presentes y en verde.
```

### Dependencias de diseño para la Tarea 6, cerradas en el debate de la Tarea 5 (2026-10-01)

- **D5a:** el enum de dominio de `identity` para `Account.platformAuthority` y su mapeo al `PlatformAuthority` de `contracts` (switch exhaustivo, sin `default`) se implementan **aquí**, no en la Tarea 5.
- **DoD obligatorio de la Tarea 6:** dos tests de `resolvePrincipal`:
  - `Account.platformAuthority = ADMINISTRATOR` → `principal.platformAuthority() == ADMINISTRATOR`;
  - `Account.platformAuthority = null` → `principal.platformAuthority() == null`.
  Además, retirar el comentario transitorio de `IdentityPrincipalPortImpl` y actualizar los `assertNull` añadidos en la Tarea 5.
- **D5d (actor del Audit Log en los comandos de plataforma):** los servicios de plataforma **no** reciben `AuthorizationPrincipal` y `AuditActor` como parámetros independientes, porque permitiría autorizar con A y atribuir a B. El actor se deriva del principal mediante **un único traductor** en `identity.application`, que se añade como excepción explícita y documentada a la regla 4 de ArchUnit (Tarea 4). La firma y ubicación exactas se deciden al preparar la Tarea 6.
- **Hueco abierto, a debatir en la Tarea 6:** la regla 4 solo analiza el paquete `identity`. Un llamador externo (el futuro `api`) podría construir `AccountAuditActor(objetivo)` y pasarlo a los 10 servicios de la Tarea 4. Decidir si esos llamadores también deben pasar por el traductor.

### Decisiones del debate de la Tarea 6 (2026-10-01)

- **D6a:** la tarea se divide en **6A** (campo `Account.platformAuthority`, persistencia, mapeo en `resolvePrincipal`, guarda de desactivación) y **6B** (singleton, política, GRANT/REVOKE, concurrencia). La 6B depende de la 6A.
- **D6b:** reglas en el Aggregate (`grantPlatformAuthority()` / `revokePlatformAuthority()`), métodos condicionales nuevos en `AccountRepositoryPort` y `PlatformAuthorityStatePort` nuevo. Si una escritura condicional da `matched == 0` dentro de la transacción, se lanza una excepción nombrada de invariante roto, que aborta todo. Bajo aislamiento de snapshot, un cambio concurrente produce un WriteConflict, no un `matched == 0`, así que solo puede ser un error de implementación.
- **D6c:** `save` reemplaza el documento completo; hay 8 rutas de escritura. Son obligatorios el test del mapper en ambos sentidos y tests de conservación en al menos dos operaciones reales (`changeCredentials`, `addEmployee`).
- **D6d:** GRANT/REVOKE usan las excepciones genéricas del helper. No se crea `PlatformAuthorityOutcomeUnknownException`. Corregir el texto de ADR-038 §2.8 al documentar.
- **D6e (APROBADA por el humano el 2026-10-01; requiere enmienda a ADR-038 §2.3.2 en la documentación):** revalidar dentro de la transacción la cuenta del actor (`ACTIVE` + `ADMINISTRATOR`) en GRANT/REVOKE. La serialización la garantiza el conflicto sobre el singleton. **No** se generaliza a la Tarea 8: allí se analiza aparte.
- **D6f:** un único `@Component` `AuthorizationAuditActorMapper` en `identity.application.authorization` (`toAuditActor(principal)`), añadido como excepción a la regla 4 de ArchUnit.
- **D6g:** la política se evalúa sobre el principal **antes** de `executeWithRetry`; la revalidación D6e va **dentro**.
- **Escenario de concurrencia añadido (e):** `REVOKE(A) ‖ DEACTIVATE(A)` → nunca termina con A `INACTIVE` y administrador.
- **Proceso:** el rojo se entrega en un commit separado y lo verifica el humano antes de implementar.

## TAREA 7 — `feat/identity-platform-bootstrap` (ADR-038 §2.4)

```
TAREA: runner de un solo uso en app que establece el primer Platform Administrator.

ENTREGABLES:
1. Activación solo con traceability.bootstrap.platform-admin.enabled=true + email de
   la cuenta destino por variable de entorno. Ninguna contraseña en configuración.
2. Precondiciones de ADR-038 §2.4 (estado inexistente, NINGUNA cuenta con
   platformAuthority, cuenta destino existente y ACTIVE), fail-closed, sin escrituras.
3. Una transacción: insertar singleton (count=1) + Account.platformAuthority + auditoría
   BOOTSTRAP_PLATFORM_AUTHORITY con actor de variante SISTEMA.
4. Cualquier fallo con el flag activo → la aplicación no arranca.
5. NUNCA invoca GrantPlatformAuthority (verify(never()) en un test).

TESTS: primera ejecución; segunda (PlatformAlreadyBootstrappedException); dos
concurrentes (exactamente una gana); cuenta inexistente; cuenta INACTIVE; cuenta destino
ya administradora; OTRA cuenta administradora sin estado
(PlatformAuthorityInconsistentStateException). Todos con aserciones negativas.

DEFINITION OF DONE: mvn test -pl identity y -pl app en verde, salidas literales.
```

### Decisiones del debate de la Tarea 7 (2026-10-01)

- **D7a:** la lógica vive en `BootstrapPlatformAuthorityService` (`identity`); en `app` solo hay un `ApplicationRunner` delgado, condicionado al flag. Precisa el ADR, no lo contradice.
- **D7b:** `PlatformAuthorityStatePort.initialize()` y `AccountRepositoryPort.existsAnyWithPlatformAuthority()`, nuevos. Se reutilizan `grantPlatformAuthorityIfAbsent` y `Account.grantPlatformAuthority()`. `version` inicial = **1** (ChatGPT proponía 0; el revisor lo rechazó porque el adaptador suma 1 por transición y el bootstrap es la primera).
- **D7c:** solo la `DuplicateKeyException` de la inserción del singleton se traduce a `PlatformAlreadyBootstrappedException`. Hay test de los dos órdenes (ganador confirmado / ganador sin confirmar). El delta de reintentos se endurece con el valor observado en MongoDB 6.0.
- **D7d:** inventario verificado por el revisor sobre `3b8ce2c`: solo 3 rutas escriben `platformAuthority` (`save` vía mapper, `grantIfAbsent`, `revokeIfHeld`) y ningún otro módulo escribe en `accounts`. Sin singleton, GRANT falla con `StateMissing`, así que la única carrera posible es entre dos bootstraps. El riesgo de `save()` que señaló ChatGPT ya está cubierto desde la 6A (D6c).
- **D7e:** `processId = "platform-bootstrap"`, constante única. Sin email en la auditoría. Regla ArchUnit nueva para `SystemAuditActor` (solo el servicio de bootstrap y el mapper), con su bite test.
- **D7f:** excepción propia `BootstrapTargetAccountNotFoundException`. Email ausente o inválido → `InvalidEmailFormatException`, reutilizada, sin tocar Mongo. Verificado en el fuente de Spring Boot `v3.4.4`: la excepción de un runner hace fallar `SpringApplication.run`.
- **D7g:** se mantiene el ADR: con el flag activo y el bootstrap ya hecho, la app no arranca. Cambiarlo sería una enmienda aparte.
- **Riesgo aceptado y a documentar:** los runners se ejecutan **después** de que arranque el servidor web, así que hay una ventana breve en la que se sirve HTTP antes de que el bootstrap termine o falle. No es explotable para conceder autoridad, porque GRANT exige el singleton.

### Decisiones del debate de la Tarea 8 (2026-10-02)

- **División:** **8A** (modelo, Value Object del mensaje, mapper fail-closed, D2, conservación en las 7 rutas de `save()`) y **8B** (los 3 servicios de plataforma, auditoría, concurrencia).
- **D8a:** el Value Object `InformationRequestMessage` aplica `strip`, rechaza el vacío y más de 2000 code points (rechaza, nunca trunca). Invariante: mensaje ⇔ `NEEDS_MORE_INFORMATION`. **El humano decidió que el mensaje se borra al pasar a `VERIFIED` o `REJECTED`.**
- **D8b:** verificado por el revisor sobre `9a68e17`: un único `OrganizationMapper` y las 7 rutas hacen `findById → mutación → save` dentro de `executeWithRetry`. Bastan el test del mapper en ambos sentidos más la conservación en `addEmployee` y `assignAdministrator`.
- **D8c:** opción A, `save()` normal del Aggregate. La serialización la da el `WriteConflict` de la transacción más el reintento. No se usan escrituras condicionales.
- **D8d:** estado desconocido o par estado/mensaje inválido → `CorruptOrganizationDocumentException` (fail-closed). D2 solo en lectura, sin reescribir documentos.
- **D8e (DECIDIDA por el humano; requiere enmienda a ADR-038 §2.6):** releer la cuenta del llamador dentro de la transacción (`ACTIVE` + `ADMINISTRATOR`). Límite a documentar: **estrecha la ventana, pero no serializa frente a REVOKE**, porque VERIFY no escribe nada que comparta con REVOKE.
- **D8f:** auditoría con `previousStatus`, `newStatus` e `informationRequestPresent`; **sin el texto del mensaje** (posible PII en un log append-only).
- **D8g (corrección del revisor a ChatGPT):** el comando de `InvalidVerificationTransitionException` es un enum del dominio, `VerificationCommand`. Usar `PlatformCommandType` (de `identity.application.authorization`) introduciría la primera dependencia del dominio hacia la capa de aplicación. Se añade una regla ArchUnit para que el dominio no dependa de application ni de infrastructure.
- **Tests de concurrencia añadidos en 8B:** `REQUEST_INFORMATION ‖ VERIFY` y `REQUEST_INFORMATION ‖ REJECT`, además de `VERIFY ‖ REJECT` y `VERIFY ‖ addEmployee`.

## TAREA 8 — `feat/identity-organization-verification` (ADR-038 §2.5, §2.6) — **NO INICIAR sin la decisión D2**

```
TAREA: verificación de Organization.

ENTREGABLES:
1. Organization.verificationStatus (4 estados) + verificationInformationRequest.
   Organización nueva → PENDING_VERIFICATION. Documento sin campo → según D2.
2. VerifyOrganization, RejectOrganization, RequestOrganizationInformation con la máquina
   de estados de ADR-038 §2.5 y la firma de §2.6. Transición no definida →
   InvalidVerificationTransitionException(currentStatus, command).
3. message: recortar extremos; vacío o > 2000 caracteres →
   InvalidInformationRequestMessageException. Rechazar, NUNCA truncar.
4. PlatformAuthorizationPolicy evaluada antes de cualquier lectura de negocio.
5. Auditoría de cada transición aceptada (acciones nuevas en AuditAction).

TESTS: todas las transiciones válidas; las 6 combinaciones inválidas desde VERIFIED y
REJECTED (estado y auditoría intactos); mensaje vacío, solo espacios, 2000 (acepta),
2001 (rechaza, y el mensaje previo se conserva); self-transition reemplaza el mensaje;
principal sin autoridad → InsufficientPlatformAuthorityException sin ninguna lectura.

QUÉ NO HACER: no toques CreateConvocatoria (su precondición VERIFIED es de ADR-037).

DEFINITION OF DONE: mvn test -pl identity en verde, salida literal.
```

---

## Documentar (último paso, tras cada merge)

Tras fusionar cada rama, y solo con la salida real de esa sesión:
- `estado-fase6.md`: fila de Identidad con lo realmente implementado.
- ADR-038 §2.2: fecha y commit de corte (al fusionar la tarea 4).
- ADR-038 §2.8: redacción final según D1.
- Al terminar las 8 tareas: cambiar `HumanAccount` → `HumanActor` en `identity-resumen.md` (documentación derivada).
- ADR-038 §2.2 — enmiendas cerradas en el debate de la Tarea 4 (a aplicar **antes** del merge de la rama):
  - **Excepción D3:** "`CreateAccountService.createAccount(Email, String)` no recibe actor: en el autorregistro la cuenta aún no existe. Registra `AccountAuditActor(<cuenta nueva>)` con `changeSummary.selfRegistration = true`. Es la única excepción a esta regla; cualquier alta hecha por otra persona exige un método nuevo que reciba el actor."
  - **Definición de "presente":** no nulo tras el mapeo de Spring Data; `actor: null` explícito equivale a ausente.
  - **`AuditRegime`:** derivado de `actor`, no almacenado.
  - **Límite del modelo de amenaza:** "La distinción PRE/POST corte detecta errores y corrupción estructural; no garantiza integridad ni autenticidad frente a quien pueda escribir directamente en MongoDB (el Audit Log de identity no tiene cadena de hashes, ADR-025)."
- `documento-maestro-proyecto.md` §4: `identity` **sí** depende de `contracts` desde `8012f87` (ADR-032/D1). Corregir "No depende de `contracts`".
- ADR-038 §2.8: nombres D4 de las excepciones y el guard de anidamiento (C+).
- **Incidente de proceso (Tarea 4, commit `558a915`):** el reporte de Antigravity presentó como literales salidas de Maven y de git que no lo eran: un ULID con fecha de 2025, líneas y llamadores de ArchUnit que no coinciden con el código, conteos por clase que no suman el total y un autor falso en `git log`. Además, su apartado de desviaciones decía "ninguna" mientras el diff borraba un comentario y cambiaba sangrías. El verde real lo ejecutó el humano (identity 119/119, app 4/4). Regla derivada: la evidencia de tests la ejecuta y pega el humano, no el agente.
- Corte del régimen del Audit Log (ADR-038 §2.2): commit `558a915` (2026-10-01).
- **Tarea 6A cerrada (2026-10-01):** commits `db046ba` (tests en rojo, verificados por el humano: 3 fallos por aserción) y `3146e75` (implementación). Verde ejecutado por el humano: identity 127/127 y reactor completo SUCCESS (core 229, crypto 50, ai 19, api 34, identity 127, app 23).
  - Incidencias:
    - el test `addEmployee` entregado por Antigravity no compilaba (variable `org` que chocaba con el paquete `org.springframework`); lo corrigió el humano;
    - Antigravity se cortó por un error de red antes del segundo commit; el commit lo hizo el humano tras revisar.
  - **Regla operativa nueva:** desde la Tarea 5, `identity` se prueba con `-am` (`mvn test -pl identity -am`); sin ello Maven usa un `contracts` desactualizado de `~/.m2`.
- **Tarea 6B cerrada (2026-10-01):** commits `3389c3d` (fase 1: política, puertos, GRANT/REVOKE, 19 tests de servicio), `4234723` (fase 2: 6 escenarios de concurrencia + `@DirtiesContext`) y `3b8ce2c` (fase 3: escenarios a/f deterministas, a2 nuevo, 3 tests secuenciales de revalidación en REVOKE). Verde ejecutado por el humano: identity 175/175 y, sobre `4234723`, reactor completo SUCCESS (core 229, crypto 50, ai 19, api 34, identity 171, app 23).
  - **Prueba de mordida (ejecutada por el humano, mutaciones no commiteadas):**
    - M1, revalidación D6e fuera de `executeWithRetry` en GRANT y REVOKE → rojos exactamente `scenario_a` (`LastPlatformAdministrator` en vez de `Insufficient`) y `scenario_f` (GRANT del administrador revocado tuvo éxito).
    - M2, revalidación borrada de REVOKE → rojos exactamente los 3 `revokePlatformAuthority_principalActor*` y `scenario_a`.
  - **Hallazgo de revisión (corregido en `3b8ce2c`):** en `4234723`, ningún test detectaba la rotura de D6e. El escenario f aceptaba un estado final idéntico para el orden legítimo y para el que viola D6e. El escenario a aceptaba `LastPlatformAdministrator`, que con D6e es inalcanzable. REVOKE no tenía tests secuenciales de revalidación.
  - **Consecuencia para el ADR (documentar en §2.3):** con D6e, el escenario a del ADR (`REVOKE(A) ‖ REVOKE(B)` con 2 administradores) termina en `InsufficientPlatformAuthorityException`, no en `LastPlatformAdministratorException`. La protección del último administrador bajo concurrencia se demuestra en a2 (`REVOKE(B) por A ‖ REVOKE(A) por A`).
  - **Hallazgo de liveness (documentar en ADR-038 §2.8):** con contención real y orden no forzado, el perdedor puede agotar los 3 intentos antes de que el ganador confirme → `IdentityConcurrentModificationException` (~1 de cada 7 ejecuciones en el escenario a original). Es un resultado permitido por §2.8: el perdedor no aplica nada. La política de reintentos no se cambió.
  - **Hallazgo de infraestructura de tests:** dos clases con la misma configuración de Spring compartían el contexto cacheado. `BaseMongoIntegrationTest` usa `@Container` estático, que para el contenedor al acabar cada clase, así que el contexto cacheado apuntaba a un puerto muerto. Arreglado con `@DirtiesContext` en las dos clases nuevas. Mejora futura: contenedor singleton en `BaseMongoIntegrationTest`.
  - **Desviaciones aceptadas:**
    - `MongoTransactionRetryHelper.getRetryCount()` pasa de package-private a `public` (lo usan los tests de `identity.application.service`).
    - `PlatformAuthorityStateDocument` solo lo usan los tests (menor).
- **Tarea 7: implementación `4218ffb` (2026-10-01).** Verde ejecutado por el humano: identity 190/190, app 27/27 y core/crypto/ai/api sin cambios.
  - **Verificación ejecutada por el humano (2026-10-02, script `verificar-tarea7.sh`, mutaciones no commiteadas):**
    - delta de reintentos real = **1** en los dos órdenes de la carrera de bootstrap;
    - M1 (sin traducir `DuplicateKeyException`) → **todo verde**: la traducción no la ejercita ningún test;
    - M2 (sin la precondición 2) → rojos exactamente los 2 tests `InconsistentState`;
    - M3 (el runner se traga la excepción) → rojos exactamente el test de arranque real y `rethrowsSameInstance`.
  - **Hallazgo empírico (documentar en ADR-038 §2.4):** en MongoDB 6.0, dentro de una transacción, insertar el `_id` del singleton que otra transacción ya insertó da `WriteConflict` (`TransientTransactionError`), **tanto si la otra ya confirmó como si no**. Por eso, la guarda real contra el doble bootstrap es reintentar y releer `exists()`, no la clave duplicada. La traducción de `DuplicateKeyException` queda como defensa para usos fuera de una transacción.
  - **Pendiente (prompt `prompt-antigravity-tarea-7-cierre.md`):**
    - fijar el delta en `== 1`;
    - quitar el stub muerto `loserFirstGrant` / `winnerGranted` del orden (ii);
    - cubrir la traducción con un test de adaptador fuera de transacción.
  - **Riesgo menor a documentar:** `InvalidEmailFormatException` (clase anterior a esta tarea) incluye el valor en su mensaje. Si el flag está activo con un email mal escrito, ese valor aparece en el log de fallo de arranque.
- **Tarea 7: commit de cierre `9a68e17` (2026-10-02).** Delta fijado en `== 1`, stub muerto eliminado y test del adaptador `initializeTwice_outsideTransaction`. identity 191/191 en verde (ejecutado por el humano). Revisado en GitHub: el diff solo toca esos 2 archivos de test. M1 sobre `9a68e17` ejecutada por el humano: rojo exactamente `platformAuthorityState_initializeTwice_outsideTransaction_throwsPlatformAlreadyBootstrapped`, el resto en verde. **Tarea 7 cerrada (2026-10-02).**
- **Incidente del revisor (2026-10-03) — regresión en `9a68e17`.** El revisor declaró "código muerto" la espera del perdedor en `grantPlatformAuthorityIfAbsent` (orden ii del bootstrap) y pidió eliminarla. La deducción salía de que el delta de reintentos era 1, sin comprobar **qué** transacción reintentaba. Sin esa espera, el test falló 7 de 10 veces.
  - **Instrumentación del humano sobre `788def5` (5 ejecuciones):** las **dos** inserciones del `_id` del singleton tienen éxito mientras ninguna ha confirmado. El conflicto aparece al **confirmar**, y pierde quien confirma en segundo lugar (reintenta → `exists()=true` → `PlatformAlreadyBootstrappedException`).
  - La seguridad se mantiene: exactamente uno gana, `count=1` y 1 auditoría.
  - **Corrige el hallazgo empírico anterior de la Tarea 7:** el `WriteConflict` al insertar solo se da cuando la otra transacción **ya confirmó** (orden i). Con la otra sin confirmar, el conflicto es al confirmar (orden ii). ADR-038 §2.4 debe decir: "gana la primera transacción que confirma".
  - Fix: prompt `prompt-antigravity-fix-bootstrap-order2.md` (el perdedor espera `winnerDone` antes de su primera escritura de cuenta, más una aserción del hallazgo).
  - Lección: un delta de reintentos no identifica qué hilo reintentó. Antes de declarar código muerto en un test de concurrencia, se instrumenta.
- **Incidente repetido (8A, `f9fb485`):** los tests rojos usaban una variable local `org` que tapaba los paquetes `org.springframework` / `org.bson`. El editor de Antigravity (ECJ) dejó en `target/` una clase que lanzaba "Unresolved compilation problems" al ejecutarse. El humano lo corrigió con `sed` y `--amend`. Regla: tras un cambio del agente, verificar con `mvn clean`.
- **8A implementada (`788def5`):** identity 229 tests (`mvn clean`, ejecutado por el humano). El único fallo es el orden (ii) del bootstrap (regresión anterior). Pendiente: revisión en GitHub.
- **2026-10-03, fix `0e2440a` y reactor completo.** El orden (ii) del bootstrap pasó 10/10 (ejecutado por el humano). Reactor con `mvn clean test` en verde: core 229, crypto 50, ai 19, api 34, identity 229, app 27. Push de `f9fb485`, `788def5` y `0e2440a`.
  - **Revisión de la 8A en GitHub:** el código coincide con el plan aprobado (VO con `strip` y límite de 2000 code points, invariante en `reconstitute`, mapper fail-closed con D2 solo en lectura, `VerificationCommand` en el dominio, regla ArchUnit de capas con su bite test). Los tests de no filtración del texto (`SECRETO-PII-*`) comprueban `getMessage()` y la causa.
  - Pendiente: mutaciones 8A (`verificar-tarea8A.sh`).
- **Tarea 8A cerrada (2026-10-04).** Mutaciones ejecutadas por el humano sobre `0e2440a`, sin commitear:
  - M1, el mapper no escribe el estado → rojos los 3 tests de conservación más el de ida y vuelta (Error por documento corrupto: fail-closed);
  - M2, `verify()` no borra el mensaje → rojo `...viaVerify_clearsMessage`;
  - M3, estado desconocido → PENDING → rojo el caso `"FOO"`.
- **Tarea 8B:** prompt `prompt-antigravity-tarea-8B.md`.
  - 3 servicios en el patrón GRANT (política → VO del mensaje → actor → tx con revalidación D8e → Aggregate → `save` → auditoría D8f).
  - 7 escenarios de concurrencia con orden forzado y aserción de qué hilo falló (lección del bootstrap).
  - **Deuda técnica a documentar:** el bloque de revalidación del llamador queda duplicado en 5 servicios. Pendiente extraerlo a un componente compartido en una tarea propia.
- **Tarea 8B (`2b2a68a`, 2026-10-04).** identity 261/261 con `mvn clean` y concurrencia nueva 5/5, ejecutado por el humano.
  - **Revisión en GitHub:** Verify y Reject son estructuralmente idénticos. Los 25 tests de servicio son: 6 transiciones válidas, 6 inválidas, 2 de mensaje, 1 sin autoridad, 9 de D8e y 1 de organización inexistente.
  - **Mutaciones ejecutadas por el humano, sin commitear:**
    - B1 (VERIFY sin relectura D8e) → rojos exactamente los casos [1], [4] y [7], es decir, los de VERIFY;
    - B2 (texto del mensaje en la auditoría) → rojos transition3 y transition6;
    - B3 (VERIFY sin política) → rojo `principalWithoutAuthority...` (verifyNoInteractions).
  - **Tarea 8 cerrada. Implementación del ADR-038 completa (tareas 1–8).** Siguiente: reactor completo y fase de documentación.
- **Reactor final sobre `2b2a68a` (2026-10-04, `mvn clean test`, ejecutado por el humano):** BUILD SUCCESS. core 229, crypto 50, ai 19, api 34, identity 261, app 27 (620 tests).
- **Documentación:** parche `docs-adr-038.patch`, redactado por el revisor y pendiente de revisión y commit del humano:
  - ADR-038: estado y §9 (commits, enmiendas E1–E6, evidencia, deuda);
  - ADR-026: enmienda aplicada y estado;
  - documento-maestro §4: `identity` → `contracts`;
  - identity-resumen: `HumanAccount` → `HumanActor`;
  - estado-fase6: fila de identidad, §5 y §7 nueva con evidencia e incidentes.
