# Plan de Ejecución — Fase 4: Módulo de Identidad y Cuentas

**Proyecto:** Motor de Trazabilidad Verificable de Donaciones (`com.traceability`)
**Base de decisiones:** ADR-025 (persistencia), ADR-026 (modelo de dominio), ADR-027 (módulo Maven) — los tres **Approved, 2026-09-08**.
**Reglas de proceso heredadas:** `reglas-equipo-y-agentes.md` completo. En particular: una rama por tarea, PR individual con aprobación humana, `mvn test` en verde en todos los módulos antes de cerrar cualquier tarea, output literal de Surefire (nunca descripción cualitativa), git status limpio al cierre, cero scope creep.

**Orden:** de lo más básico (dominio puro, sin infraestructura) a lo más complejo (orquestación transaccional cross-aggregate e integración real contra MongoDB). Ninguna tarea empieza antes de que la anterior cierre con evidencia.

---

## Guardas de ejecución con herramientas agénticas (Antigravity u otras)

Este backlog puede ejecutarse con asistencia de herramientas agénticas de codificación (por ejemplo, Antigravity, que genera automáticamente "Artifacts" — walkthroughs, capturas, resúmenes de tarea — como forma de reportar su propio trabajo; o cualquier otra con un mecanismo equivalente). Las siguientes guardas aplican **sin excepción**, sin importar qué herramienta ejecute cada tarea, y tienen prioridad sobre cualquier flujo de trabajo por defecto de la herramienta:

1. **El Artifact/walkthrough/resumen autogenerado de la herramienta NUNCA constituye evidencia de finalización por sí solo.** Es exactamente el tipo de "descripción de que debería funcionar" que `reglas-equipo-y-agentes.md` prohíbe aceptar como prueba de trabajo completado (sección 2.3). El único Definition of Done válido, para cualquier tarea de este backlog, es: el output **literal** de Surefire (`Tests run: X, Failures: Y, Errors: Z`), el `git diff` real de los archivos modificados, y `git status` limpio al cierre. Un Artifact puede acompañar la entrega como resumen legible, pero nunca la sustituye.
2. **Una tarea por entrega — nunca el backlog completo de una sola vez.** Cada tarea (4.0 a 4.10) se entrega a la herramienta de forma individual y aislada, en su propia rama. Ninguna herramienta está autorizada a encadenar autónomamente la siguiente tarea sin que el PR de la tarea anterior haya sido revisado y aprobado explícitamente por un humano (mismo criterio que `reglas-equipo-y-agentes.md` sección 3.4 exige entre agentes).
3. **Verificación de infraestructura antes de la primera tarea que use Testcontainers (4.6 en adelante):** confirmar explícitamente, con evidencia (`docker inspect` o el log de arranque de Testcontainers), que el entorno de ejecución de la herramienta levanta MongoDB configurado como **Replica Set** (no standalone) antes de correr cualquier test transaccional — condición que ADR-025 exige para las transacciones ACID. Si la herramienta reporta un fallo de transacción, la primera hipótesis a descartar es la configuración del entorno, no el diseño; nunca se acepta una "solución" que elimine, debilite o rodee la transacción ACID para hacer pasar un test.
4. **Ningún bloque de evidencia final se redacta de memoria — siempre se ensambla concatenando archivos ya volcados por la terminal.** Incidente real durante la Tarea 4.1: un agente presentó un log de Maven y un `git diff` que parecían legítimos pero contenían contenido "autocompletado" a partir de patrones típicos (una línea de copia de recursos que no ocurrió, dos excepciones distintas con el mismo hash de blob de Git) — reconstruidos de memoria en lugar de leídos literalmente. La corrección obligatoria para todo el resto del backlog: cada comando cuya salida deba citarse se redirige primero a un archivo (`comando > /tmp/archivo.log 2>&1`), y el bloque de evidencia se arma únicamente con `cat` de ese archivo — nunca retipeando ni resumiendo lo que "se vio en pantalla". Si una herramienta no puede garantizar este flujo (por ejemplo, porque solo expone el texto ya procesado de una respuesta anterior), debe decirlo explícitamente en vez de reconstruir el contenido.

---

## Decisión técnica resuelta antes de este backlog: algoritmo de hashing de `PasswordHash`

ADR-026 estableció que el dominio no conoce el algoritmo de hashing — la decisión de cuál usar quedó pendiente para este backlog. Se resuelve aquí, antes de la Tarea 4.0, porque afecta el `pom.xml` del scaffolding.

**Diagnóstico:** se necesita una función de hashing de contraseñas para `PasswordHasherPort` (adaptador de infraestructura), sin contaminar el dominio ni introducir aparato de autenticación que ADR-023 ya descartó para este proyecto.

**Evaluación de alternativas:**

| Opción | Evaluación |
|---|---|
| **BCrypt vía `spring-security-crypto`** | Librería aislada (`org.springframework.security:spring-security-crypto`), **no** trae `spring-boot-starter-security` — sin filtros, sin `SecurityFilterChain`, sin autoconfiguración. No contradice ADR-023 (que descartó el aparato de autenticación de Spring Security, no la utilidad de hashing). Estándar de la industria, factor de costo ajustable, sobradamente probado. |
| **Argon2** (misma librería, `Argon2PasswordEncoder`) | Ganador del Password Hashing Competition, más resistente a ataques por GPU/ASIC. Requiere tuning de memoria/paralelismo. Es la opción más fuerte, pero también la que menos se justifica sin un requisito de seguridad explícito que la exija — candidata a sobre-ingeniería para el alcance actual. |
| **PBKDF2** | Más débil frente a hardware especializado que las dos anteriores; sin ventaja sobre BCrypt aquí. Descartada. |

**Riesgo de traer la dependencia:** ninguno relevante — `spring-security-crypto` es un JAR pequeño y aislado, ya extensamente usado en el ecosistema Spring sin arrastrar el resto del framework de seguridad.

**Recomendación:** **BCrypt vía `spring-security-crypto`**, factor de costo 12, encapsulado detrás de `PasswordHasherPort` para que el dominio nunca lo conozca y para poder migrar a Argon2 en el futuro sin tocar `Account` si aparece un requisito real que lo justifique.

**Decisión:** adoptada. Se documenta aquí como decisión técnica de dependencia (no amerita ADR propio — no cambia límites de Aggregate, no introduce mecanismo de concurrencia, es una elección de librería de infraestructura ya prevista y delegada explícitamente al adaptador por ADR-026).

---

## Bloque 0 — Scaffolding del módulo

### TAREA 4.0 — Scaffolding del módulo Maven `identity`

**Rama:** `feat/identity-module-scaffolding` | **Depende de:** nada | **ADR:** ADR-027

```
TAREA: Crear el módulo Maven `identity` según ADR-027.

ENTREGABLES:
1. identity/pom.xml — depende de: spring-boot-starter (para @Transactional
   y contexto de Spring en infraestructura), spring-data-mongodb,
   spring-security-crypto (SOLO esta dependencia de "seguridad" — NUNCA
   spring-boot-starter-security ni spring-boot-starter-web en este módulo),
   JUnit 5, Testcontainers (mongodb), Lombok (scope provided/optional,
   igual que en `core`). NO depende de `core` ni de su infraestructura.
   NO depende de `contracts` todavía (ADR-027, sección Dependencias).
2. Estructura de paquetes:
   identity/src/main/java/identity/
   ├── domain/
   │   ├── model/
   │   └── exception/
   ├── application/
   │   └── port/
   │       ├── in/
   │       └── out/
   └── infrastructure/
       └── persistence/
3. Actualizar el pom.xml raíz para incluir el módulo `identity`.
4. Actualizar app/pom.xml para depender de `identity` (además de core,
   crypto, ai, api).
5. Verificación explícita, con evidencia (log de arranque de
   Testcontainers o `docker inspect`), de que MongoDB en el entorno de
   ejecución está configurado como Replica Set — precondición de
   ADR-025 para las transacciones ACID que las Tareas 4.6, 4.8 y 4.9
   necesitarán. Si no lo está, corregir la configuración compartida de
   Testcontainers en esta misma tarea (es la única del backlog que toca
   configuración de infraestructura compartida) y documentar el cambio.
6. Test de arquitectura (ArchUnit) que falle el build si:
   a) cualquier clase bajo identity.domain importa algo de
      org.springframework.* o com.mongodb.*;
   b) cualquier clase bajo identity.* importa algo de core.infrastructure.*.
   Demuéstralo fallando con una violación deliberada y pasando limpio tras
   eliminarla (mismo criterio que exigió la Tarea 3.0 de Fase 3).

QUÉ NO HACER: no crees ninguna clase de dominio, comando ni Value Object
todavía. No agregues spring-boot-starter-web (identity no expone HTTP en
esta fase — eso es una decisión futura, explícitamente fuera de alcance
de ADR-027).

DEFINITION OF DONE: `mvn clean install` desde la raíz compila los 7
módulos (contracts, core, crypto, ai, api, identity, app) sin errores. Los
dos tests de ArchUnit nuevos existen y pasan, con evidencia de haber
fallado antes de la corrección. Evidencia literal (log o `docker
inspect`) de que MongoDB corre como Replica Set en el entorno de
ejecución, entregada junto con el resto — no una afirmación de que "ya
debería estar configurado así".
```

---

## Bloque 1 — Dominio puro: Value Objects y excepciones

### TAREA 4.1 — Value Objects y excepciones nombradas

**Rama:** `feat/identity-value-objects` | **Depende de:** 4.0 | **ADR:** ADR-026

```
TAREA: Implementar los Value Objects y el catálogo completo de excepciones
de dominio de Identidad, sin ningún Aggregate todavía.

ENTREGABLES (identity.domain.model, records de Java 21 donde aplique):
1. AccountId, OrganizationId — wrapper opaco sobre ULID. Genera el ULID
   internamente en el momento de construcción de una nueva instancia
   (no lo recibe como parámetro externo salvo para reconstrucción desde
   persistencia).
2. Email — valida formato en el constructor compacto; lanza
   IllegalArgumentException (o una excepción de dominio propia si lo
   prefieres — repórtalo) si el formato es inválido.
3. PasswordHash — wrapper opaco de un String ya hasheado. NO valida
   formato de algoritmo (eso es responsabilidad del adaptador que lo
   produce, no de este Value Object).
4. AccountStatus — enum ACTIVE | INACTIVE.
5. OrganizationType — enum FOUNDATION | COMPANY.
6. Role — enum REPRESENTATIVE | ADMINISTRATOR | EMPLOYEE.

ENTREGABLES (identity.domain.exception):
DuplicateEmailException, AccountNotFoundException,
OrganizationNotFoundException, AccountAlreadyBelongsToOrganizationException,
AccountNotMemberOfOrganizationException, CannotRemoveLastRoleException,
RepresentativeTransferRequiredException, TransferTargetNotMemberException,
SelfTransferNotAllowedException.

QUÉ NO HACER: no crees Account, Organization, Membership ni ningún
comando todavía. No agregues lógica de negocio a los Value Objects más
allá de su propia validación de formato interno.

DEFINITION OF DONE: tests unitarios de cada Value Object (casos válidos e
inválidos) y de que cada excepción es instanciable con un mensaje
descriptivo. Cero dependencias de Spring/Mongo en este paquete —
verificado por el ArchUnit de la Tarea 4.0. Output literal de Surefire de
`mvn test -pl identity`.
```

---

## Bloque 2 — Aggregate `Account`

### TAREA 4.2 — Aggregate `Account`

**Rama:** `feat/identity-account-aggregate` | **Depende de:** 4.1 | **ADR:** ADR-026

```
TAREA: Implementar el Aggregate Root `Account` completo, con sus cuatro
comandos e invariantes, en Java puro (sin Spring, sin Mongo).

ENTREGABLES (identity.domain.model.Account):
1. Estado interno: accountId, email, passwordHash, status, organizationId
   (nullable).
2. CreateAccount(email, passwordHash) — factory estático; no valida
   unicidad de email aquí (eso requiere consultar persistencia — vive en
   el Application Service de la Tarea 4.8, no en el Aggregate).
3. changeCredentials(newPasswordHash) — rechaza si status == INACTIVE.
4. deactivate() — rechaza si ya está INACTIVE (o decide que sea idempotente
   sin excepción — elige uno de los dos comportamientos y documéntalo en
   el resumen de la tarea; no lo dejes ambiguo).
5. reactivate() — simétrico al anterior.
6. joinOrganization(organizationId) / leaveOrganization() — mutan
   únicamente organizationId; NO validan aquí las reglas de Organization
   (pertenencia única, invariantes de Membership) — esas se validan en
   el Application Service de la Tarea 4.9, que coordina ambos Aggregates
   dentro de la misma transacción. Este método solo protege que
   joinOrganization no se llame si organizationId ya no es null (usa
   AccountAlreadyBelongsToOrganizationException).

QUÉ NO HACER: no valides aquí unicidad de email contra una base de datos
que este Aggregate no conoce. No implementes Organization ni Membership.

DEFINITION OF DONE: tests unitarios cubriendo cada comando en sus casos
válidos e inválidos, incluyendo el comportamiento elegido en el punto 4
documentado explícitamente en un test que lo verifique. Output literal de
Surefire de `mvn test -pl identity`.
```

---

## Bloque 3 — Aggregate `Organization` (de lo básico a lo complejo)

### TAREA 4.3 — Aggregate `Organization`: creación, incorporación y roles simples

**Rama:** `feat/identity-organization-aggregate-basic` | **Depende de:** 4.1 | **ADR:** ADR-026

```
TAREA: Implementar `Organization` y `Membership` con las operaciones que
NO tocan el invariante de Representative único (esa parte, más compleja,
es la Tarea 4.4).

ENTREGABLES (identity.domain.model):
1. Membership — accountId + roles: Set<Role>. Invariante propio:
   roles nunca vacío (verificado en cada mutación que lo produzca).
2. Organization — organizationId, type, members: List<Membership>.
3. CreateOrganization(type, initialRepresentativeAccountId) — factory
   estático; nace con members = [Membership(initialRepresentativeAccountId,
   {REPRESENTATIVE})]. (La validación de que la cuenta inicial no
   pertenece ya a otra organización vive en el Application Service,
   igual que en Account — el Aggregate no consulta otros Aggregates.)
4. addEmployee(accountId) — rechaza con AccountAlreadyBelongsToOrganizationException
   si accountId ya es miembro de ESTA Organization (la validación de que
   no pertenece a OTRA organización es responsabilidad del Application
   Service). Crea Membership{EMPLOYEE}.
5. assignAdministrator(accountId) — rechaza con
   AccountNotMemberOfOrganizationException si no es miembro. Si ya tiene
   ADMINISTRATOR, es no-op (retorna sin mutar — el Aggregate debe exponer
   de alguna forma si hubo mutación o no, para que el Application Service
   sepa si debe generar Audit Log; decide el mecanismo — valor de retorno
   booleano, o lista de eventos de dominio internos — y documéntalo).
6. removeAdministrator(accountId) — rechaza con CannotRemoveLastRoleException
   si ADMINISTRATOR es su único rol.
7. removeEmployee(accountId) — simétrico, mismo criterio de rechazo.

QUÉ NO HACER: no implementes todavía transferRepresentativeAndRemove ni
removeMemberFromOrganization — son la Tarea 4.4, deliberadamente separada
por ser la parte del dominio con más historial de correcciones en esta
sesión de diseño.

DEFINITION OF DONE: tests unitarios de cada comando, incluyendo: rechazo
de membresía duplicada, rechazo de asignación sin membresía previa,
idempotencia verificada de assignAdministrator (con test que confirme que
NO se marca como mutación en el segundo intento), rechazo de dejar un rol
vacío vía removeAdministrator/removeEmployee. Output literal de Surefire
de `mvn test -pl identity`.
```

### TAREA 4.4 — Aggregate `Organization`: transferencia de representación y expulsión

**Rama:** `feat/identity-organization-aggregate-representative` | **Depende de:** 4.3 | **ADR:** ADR-026

> Esta es la tarea de mayor riesgo del backlog — protege el invariante que motivó más rondas de corrección en el diseño (Representative único, `Membership` nunca vacía). Léete la sección "Transferencia del Representative" y "Expulsión total" de ADR-026 completa antes de escribir una sola línea.

```
TAREA: Implementar transferRepresentativeAndRemove y
removeMemberFromOrganization sobre el Organization de la Tarea 4.3.

ENTREGABLES:
1. transferRepresentativeAndRemove(currentRepresentativeAccountId,
   newRepresentativeAccountId):
   a. verifica que currentRepresentativeAccountId POSEE efectivamente
      REPRESENTATIVE en el estado actual — si no, rechaza (usa
      AccountNotMemberOfOrganizationException si ni siquiera es miembro;
      si es miembro pero sin ese rol, decide y documenta qué excepción
      usas, ya que ADR-026 deja este sub-caso abierto a la
      implementación);
   b. verifica que newRepresentativeAccountId es miembro de esta
      Organization (TransferTargetNotMemberException si no);
   c. verifica que ambos accountId son distintos
      (SelfTransferNotAllowedException si no);
   d. transfiere REPRESENTATIVE al sucesor;
   e. si el saliente queda con roles vacío tras quitarle REPRESENTATIVE,
      elimina su Membership completa de members (NUNCA deja
      roles = {} persistido, ni siquiera en el estado en memoria antes
      de retornar).
2. removeMemberFromOrganization(accountId):
   a. rechaza con RepresentativeTransferRequiredException si el miembro
      objetivo tiene REPRESENTATIVE en su Set<Role> — sin excepción, sin
      atajo;
   b. si no lo tiene, elimina la Membership completa.

QUÉ NO HACER: no agregues un comando TransferRepresentative sin remoción
(descartado explícitamente en ADR-026). No hagas que
removeMemberFromOrganization delegue silenciosamente a
transferRepresentativeAndRemove — deben seguir siendo dos comandos
distintos con guardas cruzadas explícitas, no uno con parámetros
opcionales.

DEFINITION OF DONE: tests unitarios exhaustivos, incluyendo explícitamente
los dos ejemplos ya congelados en ADR-026 (saliente solo con
REPRESENTATIVE → Membership eliminada; saliente con REPRESENTATIVE +
otro rol → Membership permanece con el rol restante), más: rechazo si el
"actual" no es realmente Representative, rechazo de auto-transferencia,
rechazo de sucesor no-miembro, rechazo de remoción directa de un
Representative vía removeMemberFromOrganization. Verificación explícita
(assert) de que en NINGÚN camino de ejecución queda una Membership con
roles vacío, ni siquiera transitoriamente dentro del método. Output
literal de Surefire de `mvn test -pl identity`.
```

---

## Bloque 4 — Puertos de aplicación

### TAREA 4.5 — Puertos de salida (`port.out`)

**Rama:** `feat/identity-repository-ports` | **Depende de:** 4.2, 4.4 | **ADR:** ADR-025, ADR-026

```
TAREA: Definir las interfaces de puertos de salida, sin ninguna
implementación.

ENTREGABLES (identity.application.port.out):
1. AccountRepositoryPort — findById(AccountId): Account (lanza
   AccountNotFoundException si no existe), findByEmail(Email):
   Optional<Account>, save(Account): void.
2. OrganizationRepositoryPort — findById(OrganizationId): Organization
   (lanza OrganizationNotFoundException), save(Organization): void.
3. AuditLogPort — record(AuditLogEntry): void. AuditLogEntry es un record
   de identity.domain.model (o identity.application, decide y
   documenta): auditId, occurredAt, actorAccountId, targetAccountId
   (nullable), targetOrganizationId (nullable), action (enum cerrado, ver
   ADR-026), changeSummary: Map<String,Object>.
4. PasswordHasherPort — hash(String plainPassword): PasswordHash,
   matches(String plainPassword, PasswordHash hash): boolean.

QUÉ NO HACER: no implementes ningún adaptador todavía. No agregues
métodos "por si acaso" que ningún comando de ADR-026 necesite.

DEFINITION OF DONE: el módulo `identity` compila con estas interfaces sin
ninguna dependencia de Mongo en el paquete port.out. Output literal de
`mvn compile -pl identity`.
```

---

## Bloque 5 — Adaptadores de infraestructura

### TAREA 4.6 — Documentos MongoDB y adaptadores de repositorio

**Rama:** `feat/identity-mongo-documents` | **Depende de:** 4.5 | **ADR:** ADR-025

```
TAREA: Implementar AccountDocument, OrganizationDocument,
MembershipDocument, AuditLogEntryDocument y los adaptadores que
implementan los puertos de la Tarea 4.5.

ENTREGABLES (identity.infrastructure.persistence):
1. AccountDocument (@Document("accounts")) — accountId como @Id, email
   con @Indexed(unique = true), passwordHash, status, organizationId
   nullable.
2. OrganizationDocument (@Document("organizations")) — organizationId
   como @Id, type, members: List<MembershipDocument>.
3. MembershipDocument — accountId, roles: Set<String> (o el enum
   directamente si el driver de Spring Data lo serializa limpio —
   verifícalo con un test, no lo asumas).
4. AuditLogEntryDocument (@Document("identity_audit_log")) — auditId
   como @Id, más los campos de AuditLogEntry. Índices:
   {targetOrganizationId: 1, occurredAt: -1} y
   {targetAccountId: 1, occurredAt: -1}.
5. Mappers explícitos Domain ↔ Document en ambas direcciones (NO uses
   ModelMapper/MapStruct si el proyecto no los tiene ya aprobados en otro
   módulo — mapeo manual, igual que el resto del proyecto).
6. MongoAccountRepositoryAdapter, MongoOrganizationRepositoryAdapter,
   MongoAuditLogAdapter implementando los puertos correspondientes. El
   AuditLogAdapter NO debe exponer ningún método de update/delete, ni
   siquiera privado reutilizable — solo insert.

QUÉ NO HACER: no implementes todavía la orquestación transaccional
cross-aggregate (eso es la Tarea 4.9). Estos adaptadores son CRUD simple
por Aggregate individual.

DEFINITION OF DONE: tests de integración contra Testcontainers real
verificando persistencia y recuperación fiel de cada documento
(round-trip Domain → Document → Mongo → Document → Domain), y que el
índice único de email rechaza duplicados a nivel de base de datos como
segunda línea de defensa (aunque la unicidad real se valida en el
Application Service). Output literal de Surefire de
`mvn test -pl identity`.
```

### TAREA 4.7 — Adaptador de hashing de contraseñas

**Rama:** `feat/identity-password-hasher-adapter` | **Depende de:** 4.5 | **Decisión:** BCrypt vía `spring-security-crypto` (ver sección previa a este backlog)

```
TAREA: Implementar BCryptPasswordHasherAdapter sobre PasswordHasherPort.

ENTREGABLES (identity.infrastructure.security):
1. Dependencia spring-security-crypto ya agregada en la Tarea 4.0 —
   verifica que NO arrastró spring-boot-starter-security transitivamente
   (revisa `mvn dependency:tree -pl identity` y repórtalo explícitamente).
2. BCryptPasswordHasherAdapter usando BCryptPasswordEncoder con factor de
   costo 12.

QUÉ NO HACER: no expongas BCryptPasswordEncoder ni ninguna clase de
spring-security-crypto fuera de este adaptador — PasswordHasherPort es la
única superficie que el resto del módulo conoce.

DEFINITION OF DONE: tests unitarios de hash()/matches() (incluyendo que
dos hashes del mismo password son distintos entre sí por el salt, y que
ambos matchean contra el password original). Output literal de Surefire
de `mvn test -pl identity`.
```

---

## Bloque 6 — Application Services (orquestación transaccional)

### TAREA 4.8 — Application Service de `Account` (transacción simple, un solo Aggregate)

**Rama:** `feat/identity-account-application-service` | **Depende de:** 4.6, 4.7

```
TAREA: Orquestar los cuatro comandos de Account con validación de
unicidad de email y generación de Audit Log.

ENTREGABLES (identity.application.service):
1. CreateAccountService — valida unicidad de email vía
   AccountRepositoryPort.findByEmail antes de crear; hashea el password
   vía PasswordHasherPort; persiste; registra AuditLogEntry
   (ACCOUNT_CREATED) — todo dentro de un único @Transactional.
2. ChangeCredentialsService, DeactivateAccountService,
   ReactivateAccountService — mismo patrón, un solo Aggregate por
   transacción.

QUÉ NO HACER: no implementes todavía ningún comando que toque
Organization — eso es la Tarea 4.9, deliberadamente separada porque
requiere el mecanismo de reintento que esta tarea no necesita (un solo
documento, sin operación cross-aggregate, el riesgo de conflicto
transitorio es mucho menor y no amerita el mismo tratamiento).

DEFINITION OF DONE: tests de integración con Testcontainers: creación
exitosa, rechazo por email duplicado (verificando que NO se genera Audit
Log en el rechazo), cambio de credenciales, des/reactivación. Output
literal de Surefire de `mvn test -pl identity`.
```

### TAREA 4.9 — Application Service de `Organization` (transacción cross-aggregate + reintento)

**Rama:** `feat/identity-organization-application-service` | **Depende de:** 4.8

> Tarea más compleja del backlog completo — implementa literalmente ADR-025 y ADR-026 juntos: transacción ACID cross-aggregate, política de reintento acotada, y la guardia que separa fallos deterministas de conflictos transitorios.

```
TAREA: Orquestar los siete comandos de Organization, incluyendo los que
mutan Account.organizationId en la misma transacción.

ENTREGABLES:
1. CreateOrganizationService — valida que la cuenta inicial existe y no
   pertenece ya a otra Organization (consulta AccountRepositoryPort);
   crea Organization; fija Account.organizationId; ambos save() dentro
   de la misma transacción; Audit Log (ORGANIZATION_CREATED).
2. AddEmployeeService — valida pertenencia única (Account.organizationId
   == null); muta ambos Aggregates; Audit Log (EMPLOYEE_ADDED).
3. AssignAdministratorService, RemoveAdministratorService,
   RemoveEmployeeService — mutan solo Organization; Audit Log solo si el
   Aggregate reportó mutación real (ver mecanismo de la Tarea 4.3, punto
   5 — AssignAdministrator NO genera Audit Log en su camino no-op).
4. RemoveMemberFromOrganizationService — muta ambos Aggregates
   (Organization.members y Account.organizationId = null); Audit Log
   (MEMBER_REMOVED).
5. TransferRepresentativeAndRemoveService — muta Organization y,
   condicionalmente, Account.organizationId del saliente; Audit Log
   (REPRESENTATIVE_TRANSFERRED).
6. Mecanismo de reintento acotado (ver ADR-025): envuelve las
   operaciones 1, 2, 4 y 5 (las que tocan ambos Aggregates) en un bucle
   de máximo 3 intentos ante TransientTransactionError; cada intento
   recarga ambos Aggregates desde cero y reevalúa el comando completo;
   una excepción de dominio (cualquiera del catálogo de ADR-026) rompe
   el bucle inmediatamente y se propaga sin reintentar.

QUÉ NO HACER: no reintentes una excepción de dominio. No compartas el
Aggregate cargado entre intentos del bucle de reintento — cada intento
relee desde el repositorio.

DEFINITION OF DONE: tests de integración con Testcontainers cubriendo,
como mínimo: los dos escenarios de transferencia de ADR-026, rechazo de
remoción directa de Representative, un test que fuerce
TransientTransactionError real (dos transacciones concurrentes sobre el
mismo Organization) y verifique que el reintento resuelve correctamente
sin duplicar mutaciones, y un test que fuerce una excepción de dominio
dentro del bucle y verifique que NO se reintenta (a lo sumo un intento).
Output literal de Surefire de `mvn test -pl identity` — el módulo
completo, no solo las clases nuevas.
```

---

## Bloque 7 — Verificación end-to-end

### TAREA 4.10 — Suite de integración completa del módulo

**Rama:** `feat/identity-integration-tests` | **Depende de:** 4.9

```
TAREA: Cerrar Fase 4 con una suite de integración que ejercite el módulo
completo contra Testcontainers real, sin mocks de persistencia.

ENTREGABLES: tests de extremo a extremo por escenario de negocio
completo (no por método aislado), por ejemplo: "una Organization nace,
incorpora tres empleados, promueve a uno a Administrator, transfiere la
representación dos veces, y termina con el Audit Log reflejando
exactamente las mutaciones reales (ni una de más por los no-ops, ni una
de menos)".

DEFINITION OF DONE: reactor completo (7 módulos) en verde. Output
literal de Surefire de `mvn clean test` desde la raíz. `git status`
limpio. Resumen de decisiones de implementación tomadas durante el
backlog (nombres de excepciones elegidos donde ADR-026 dejó el sub-caso
abierto, mecanismo elegido para reportar mutación/no-op) entregado para
revisión humana — no oculto dentro del código sin mencionarlo.
```

---

## Resumen de ramas

| Tarea | Rama | Depende de |
|---|---|---|
| 4.0 | feat/identity-module-scaffolding | — |
| 4.1 | feat/identity-value-objects | 4.0 |
| 4.2 | feat/identity-account-aggregate | 4.1 |
| 4.3 | feat/identity-organization-aggregate-basic | 4.1 |
| 4.4 | feat/identity-organization-aggregate-representative | 4.3 |
| 4.5 | feat/identity-repository-ports | 4.2, 4.4 |
| 4.6 | feat/identity-mongo-documents | 4.5 |
| 4.7 | feat/identity-password-hasher-adapter | 4.5 |
| 4.8 | feat/identity-account-application-service | 4.6, 4.7 |
| 4.9 | feat/identity-organization-application-service | 4.8 |
| 4.10 | feat/identity-integration-tests | 4.9 |
