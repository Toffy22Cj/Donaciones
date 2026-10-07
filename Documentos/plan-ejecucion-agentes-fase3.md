# Plan de Ejecución para Agentes de Código — Fase 3: Implementación

**Proyecto:** Motor de Trazabilidad Verificable de Donaciones (nombre comercial provisional PaxFide, no usar en código: package base `com.traceability`)
**Fase:** 3 — Exposición REST de Lectura (Fase 2 formalmente cerrada, incluyendo auditoría y sus 13 hallazgos)
**Diseño de referencia:** `estado-fase3.md` (contratos congelados, 3 endpoints tras el arbitraje de ADR-024) y `plan-detallado-fase3.md`
**Audiencia:** agentes de código autónomos y los ingenieros que los supervisan.
**Nota de versión:** este documento es AUTOCONTENIDO — las 14 tareas (3.0 a 3.13) están completas aquí, sin referencias a "versiones anteriores" de este archivo. Cualquier versión previa queda obsoleta y no debe consultarse.

---

## 0. Cómo usar este documento

Cada Tarea de la sección 4 es un prompt autocontenido. El Prompt Maestro (sección 1) va siempre primero. Ninguna tarea marcada como bloqueada se ejecuta hasta que la anterior haya sido revisada y aprobada por un humano.

---

## 1. Prompt Maestro de Contexto (pegar en cada sesión de agente de Fase 3)

```
Eres un Ingeniero de Software Senior implementando el módulo `api` (y las
extensiones de `core`/`ai` que ese módulo necesita) de un sistema de
trazabilidad de donaciones basado en Event Sourcing, ya en Fase 3.

STACK OBLIGATORIO (sin cambios respecto a Fase 2):
- Java 21, Spring Boot 3.4.4, Maven, MongoDB, Spring Data MongoDB, JUnit 5,
  Testcontainers. Lombok solo si aporta valor real.
- NO introducir Spring Security (ADR-023). NO introducir JWT. NO introducir
  ninguna dependencia nueva sin detenerte a preguntar primero.

REGLA DE ORO: no eres un generador de código que maximiza líneas escritas.
Eres un implementador disciplinado de un contrato ya cerrado. Si no puedes
señalar el ADR o la sección de estado-fase3.md/plan-detallado-fase3.md que
justifica una decisión, DETENTE y pregunta — nunca improvises el contrato
por tu cuenta, aunque te parezca evidente cuál debería ser.

CATALOGO DE ADRs DE FASE 3:

- ADR-020: Modulo `api` nuevo. app -> api -> core (solo puertos de
  aplicacion). api NUNCA importa core.infrastructure.* directamente.
- ADR-021-A/B/C/D: Tracking code bearer, stateless, HMAC-SHA-256 con
  dominio versionado "tracking:v1:", MessageDigest.isEqual() para
  comparacion, expiracion embebida, revocacion dispersa con TTL,
  generacion FUERA de Fase 3, perimetro de exposicion ya clasificado.
- ADR-023: Sin Spring Security. OncePerRequestFilter propio.
- ADR-024 (Approved with amendment): Narrativa de IA expuesta como
  ENDPOINT INDEPENDIENTE, nunca embebida en el DTO principal. Ver seccion
  3 y 4 de estado-fase3.md. NarrativeReadPort vive en `contracts` (unica
  excepcion a la regla general de que los puertos de api->core viven en
  core.application.port.out -- aqui SI va en contracts porque el puerto
  cruza hacia `ai`, que nunca puede depender de `core` ni de `api`).

REGLAS DE CODIGO NO NEGOCIABLES:
1. api.* nunca contiene logica de negocio, nunca accede a MongoDB
   directamente, nunca importa core.infrastructure.projection.mongo.
   documents.* ni ninguna clase interna de `ai` (DonorReportDTO, CacheKey,
   NarrativeCacheCoordinator quedan siempre dentro de `ai`).
2. DTOs publicos son records inmutables. Nunca se expone
   DonationProjectionDocument, DonationReadModel, ni DonorReportDTO
   directamente como respuesta HTTP.
3. Dos secretos HMAC distintos (tracking code, assetRef) -- nunca el mismo
   valor, nunca compartidos entre si.
4. Constructor injection siempre.
5. Toda excepcion de autorizacion responde con el MISMO codigo HTTP 401 y
   el mismo formato de error -- nunca reveles la causa especifica. El
   resultado interno de validacion (ej. fundId asociado a un token) nunca
   debe ser accesible si el token no es valido -- estructuralmente
   imposible de filtrar, no solo una convencion que otra capa recuerda.
6. El recurso principal de tracking (GET /tracking/{code}) SIEMPRE responde
   200 si el token es valido, independientemente del estado de cualquier
   otro subsistema (incluida la narrativa, que vive en su propio endpoint).

SI DETECTAS UNA CONTRADICCION, AMBIGUEDAD, O CASO NO CUBIERTO: DETENTE.
Documenta el conflicto exacto y espera instruccion. Si el contrato de una
tarea parece faltante o incompleto en este documento, NO lo reconstruyas
por tu cuenta ni asumas cual "deberia ser" -- reportalo y espera a que se
te entregue el contrato completo.

===============================================================================
REGLAS DE PROCESO -- obligatorias en TODA tarea, sin excepcion.
===============================================================================

1. COMMITS PEQUENOS Y FRECUENTES dentro de cada tarea.

2. UNA RAMA POR TAREA, SIEMPRE NUEVA DESDE `develop` ACTUALIZADO. Nunca
   apiles el trabajo de una tarea nueva sobre la rama de otra tarea ya
   cerrada -- si te encuentras haciendo un commit sobre una rama que ya
   corresponde a otra tarea distinta a la que tienes asignada, DETENTE:
   crea la rama correcta desde `develop` primero.

3. CERO SCOPE CREEP, INCLUSO SI PARECE TRIVIAL. Si encuentras algo fuera
   del alcance exacto de tu tarea -- un bug, una mejora, "ya que estoy
   aqui" -- NO LO TOQUES. Reportalo como hallazgo separado y detente ahi.

4. NUNCA UN RESUMEN NARRATIVO SIN LA EVIDENCIA QUE LO RESPALDE.
   - Codigo: output LITERAL de Surefire del modulo completo
     (mvn test -pl <modulo>), nunca solo la clase nueva, nunca un resumen.
   - Documentacion: git diff real en el MISMO turno en que afirmas haber
     editado algo.
   - Si tu sesion sufrio cualquier anomalia (mensaje duplicado, error del
     sistema, resultado que no cuadra con lo esperado): dilo explicitamente
     y vuelve a verificar con comandos reales antes de reportar como hecho.

5. CIERRE DE TAREA OBLIGATORIO: git status completo al final, confirmando
   arbol limpio, sin scripts de trabajo temporales (fix_*.py, update_*.py,
   scratch.*, *_test_output.txt, get_*.py) en la raiz ni fuera de src/.

6. Si algo en tu propia sesion no cuadra: NO reescribas la narrativa para
   que cuadre. Detente, investiga con comandos reales, reporta la
   discrepancia exactamente como la encontraste.
```

---

## 2. Reglas de Ejecucion (Definition of Done)

Las siete condiciones de Fase 2 aplican sin cambios: compila sin dependencias circulares, cero infraestructura filtrada a `api`, test negativo por cada invariante, referencia a ADR en Javadoc, sin campos no contratados, resumen de decisiones para revision humana, sin TODOs sin marcar.

**Anadido de autorizacion:** todo test de autorizacion (token invalido, expirado, revocado, cross-fund) usa HTTP status y body reales de una peticion simulada (MockMvc/WebTestClient), nunca solo el metodo Java en aislamiento.

**Anadido de proceso:** ninguna tarea se considera cerrada sin el git status final limpio y la evidencia literal correspondiente, entregados en el mismo turno donde se afirma el trabajo hecho.

---

## 3. Estrategia de Ramas

Partir de `develop` actualizado -- confirmar antes de crear cualquier rama. Una rama por tarea, PR individual con aprobacion humana, nunca acumular varias tareas en una sola rama.

| Tarea | Rama |
|---|---|
| 3.0 | feat/api-module-scaffolding |
| 3.1 | feat/core-donation-read-port |
| 3.2 | feat/core-tracking-secret-infra |
| 3.3 | feat/core-tracking-code-service |
| 3.4 | feat/api-tracking-auth-filter |
| 3.5 | feat/core-asset-index-authorization |
| 3.6 | feat/core-location-reference |
| 3.7 | feat/core-asset-ref-service |
| 3.8 | feat/api-donation-mapper |
| 3.9 | feat/api-donation-tracking-controller |
| 3.10 | feat/api-asset-history-endpoint |
| 3.11 | feat/contracts-narrative-read-port |
| 3.12 | feat/ai-narrative-port-adapter |
| 3.13 | feat/api-narrative-endpoint |

---

## 4. Backlog Secuencial de Tareas

### TAREA 3.0 -- Scaffolding del modulo `api`

**Estado:** ✅ COMPLETADA | **Rama:** feat/api-module-scaffolding | **Depende de:** nada

> Cerrada con evidencia: ArchitectureTest de `api` demostrado fallando con una violación deliberada (`DummyViolation.java`) y pasando limpio tras eliminarla — confirma que la regla detecta violaciones reales, no solo que nunca se activa. Reactor completo (6 módulos) en verde.

```
TAREA: Crear el modulo Maven `api` segun ADR-020.

ENTREGABLES:
1. api/pom.xml -- depende de `core` (solo sus puertos de aplicacion) y de
   `contracts`. Incluye spring-boot-starter-web (primera vez que este
   starter entra al proyecto -- justificalo brevemente en tu resumen aunque
   ya este aprobado por ADR-020). NO incluye spring-boot-starter-security.
2. Actualiza el pom.xml raiz para incluir el modulo `api`.
3. Actualiza app/pom.xml para depender de `api` (ademas de core, crypto, ai).
4. Crea una regla ArchUnit que falle el build si cualquier clase bajo
   `api.*` importa algo de `core.infrastructure.*`.

QUE NO HACER: no crees ningun controlador ni DTO todavia. No agregues
Spring Security.

DEFINITION OF DONE: mvn clean install desde la raiz compila los 5
modulos (contracts, core, crypto, ai, api) sin errores. El test de
ArchUnit nuevo existe y pasa.
```

---

### TAREA 3.1 -- DonationReadPort + DonationReadModel

**Estado:** ✅ COMPLETADA | **Rama:** feat/core-donation-read-port | **Depende de:** nada

> Cerrada con evidencia: `DonationReadModel`/`LogisticsReadItem` sin ningún campo excluido de ADR-021-D (sin `AllocationModel`, sin linaje). `confirmedAllocationAmount` calculado en el adaptador. 106 tests en `core`, reactor completo en verde.

```
TAREA: Crear el puerto de lectura que expone core hacia api sin filtrar
infraestructura interna (ADR-020).

CONTEXTO YA CONGELADO:
- DonationReadPort vive en core.application.port.out, junto a
  EventStorePort y OutboxPort -- NO en contracts (contracts es para cruces
  hacia crypto/ai, no para api, que ya puede depender de core directamente).
- DonationReadModel es un record de Java 21 puro, sin anotaciones de
  Spring/Mongo, que NO es una copia disfrazada de
  DonationProjectionDocument -- solo lo que core decide exponer.

ENTREGABLES:
1. Antes de disenar el record, verifica el estado REAL y actual de
   DonationProjectionDocument (evoluciono en Tarea 10.1/10.2/10.3/10.4) --
   cita el archivo completo tal como esta hoy.
2. DonationReadModel con los campos necesarios para construir
   PublicDonationTrackingDTO segun el perimetro de ADR-021-D -- todo lo
   exponible/enmascarable, nada de lo excluido (sourceTransactionId,
   allocationId, requirementId, sourceAllocationId, parentAssetRef,
   rootAssetRef, statusBeforeSplit, AuditMetadata, donorRef). Estructura
   sugerida (ajusta si el estado real de la Tarea 1 lo exige, y reportalo):

   record DonationReadModel(
       String fundId, String currency, String campaignRef,
       long originalAmount, long clearedAmount, long pendingAllocationAmount,
       long confirmedAllocationAmount, long refundedAmount, String status,
       List<LogisticsReadItem> logistics
   )
   record LogisticsReadItem(
       String assetId, String lifecycleStatus, String assetType,
       String unitOfMeasure, BigDecimal quantity, String currentLocation,
       String currentCustodian
   )

3. Interfaz: Optional<DonationReadModel> findByFundId(String fundId).
4. Adaptador en core.infrastructure que consulta el repositorio Mongo
   existente, mapea, y calcula confirmedAllocationAmount AQUI (filtrando
   allocations por status==CONFIRMED y sumando) -- nunca en api.

QUE NO HACER: no toques DonationProjectionDocument ni
DonationProjectionHandler. No crees nada dentro de api en esta tarea.

DEFINITION OF DONE: tests: fundId existente con mezcla PENDING/CONFIRMED
-> monto derivado correcto (caso de referencia: 100000 CONFIRMED + 50000
PENDING + 200000 CONFIRMED -> 300000); fundId inexistente ->
Optional.empty(); currency/campaignRef null (legacy v1) -> sin error.
Output literal de Surefire de mvn test -pl core completo.
```

---

### TAREA 3.2 -- Infraestructura de secretos compartidos

**Estado:** COMPLETADA | **Rama:** feat/core-tracking-secret-infra

```
TAREA: Definir la configuracion para los dos secretos HMAC de Fase 3
(tracking code y assetRef -- ADR-021-B y ADR-021-D), DOS secretos distintos.

ENTREGABLES:
1. Clase @ConfigurationProperties (TrackingSecurityProperties) con
   trackingCodeSecret y assetRefSecret, leidos desde variables de entorno
   (${TRACKING_CODE_SECRET}, ${ASSET_REF_SECRET}), sin defaults hardcoded.
2. Decide y justifica en que modulo vive -- core, dado que ambos secretos
   los consume codigo de core (Tarea 3.3 y 3.7).
3. Archivo .env.example en la raiz, documentando ambas variables sin
   valores reales, con instruccion de generacion (openssl rand -hex 32).
4. Configuracion de test con valores de prueba.

DEFINITION OF DONE: ApplicationContextLoadTest en verde con las variables
configuradas; falla explicita y clara si falta alguna; output literal de
Surefire de mvn test -pl core completo; git status limpio.
```

---

### TAREA 3.3 -- Servicio de tracking code

**Estado:** COMPLETADA | **Rama:** feat/core-tracking-code-service | **Depende de:** 3.2

```
TAREA: Implementar validacion del tracking code (ADR-021-B).

CONTEXTO YA CONGELADO -- formato exacto:
Base64URL(fundId + "|" + expiryTimestamp) + "." +
Base64URL(HMAC-SHA-256("tracking:v1:" + fundId + "|" + expiryTimestamp))

ENTREGABLES:
1. TrackingCodeService: generate(fundId, expiry), validate(token),
   revoke(tokenHash).
2. TrackingCodeValidationResult: fundId como Optional<String>, PRESENTE
   UNICAMENTE cuando valid==true -- nunca accesible en ningun caso de
   fallo (revocado, expirado, HMAC invalido, formato corrupto).
   FailureReason (enum interno) nunca cruza hacia api ni hacia ninguna
   respuesta accesible al cliente.
3. Comparacion de HMAC via MessageDigest.isEqual(), nunca equals().
4. Coleccion revoked_tracking_codes (tokenHash: SHA-256 hex del token
   completo, revokedAt), TTL de 365 dias (31536000s) sobre revokedAt.

DEFINITION OF DONE: tests unitarios (valido, expirado, formato invalido,
HMAC alterado, payload manipulado) + test de integracion de revocacion
contra Testcontainers real. Output literal de Surefire de
mvn test -pl core completo.
```

---

### TAREA 3.4 -- OncePerRequestFilter de autorizacion

**Estado:** ✅ COMPLETADA | **Rama:** feat/api-tracking-auth-filter | **Depende de:** 3.0, 3.3

> Cerrada con evidencia: 6 métodos `@Test` independientes (no un solo test monolítico), los 5 casos de fallo comparados contra un body 401 canónico único. Corregido en el camino: un falso positivo de ArchUnit causado por `scanBasePackages` con string en el test, resuelto con `@TestConfiguration` explícito.

```
TAREA: Filtro de autorizacion HTTP del tracking code (ADR-021-A, ADR-023).

ENTREGABLES:
1. TrackingCodeAuthFilter extends OncePerRequestFilter, @Component en api,
   aplicado a rutas bajo /api/v1/donations/tracking/**.
2. Extrae token de header Authorization ("Bearer <token>").
3. Invoca TrackingCodeService.validate(). Invalido por CUALQUIER causa ->
   401 con el MISMO cuerpo de error (RFC 7807 Problem Details), sin
   importar la causa real.
4. Valido -> expone el fundId resuelto al controlador (decide el
   mecanismo: request attribute u objeto de contexto, justifica).

DEFINITION OF DONE: tests de integracion con MockMvc/WebTestClient sobre
un endpoint de prueba: sin header -> 401; malformado -> 401; HMAC alterado
-> 401; expirado -> 401; revocado -> 401 (Testcontainers); valido -> pasa
con fundId correcto. Los bodies de los 401 deben ser identicos entre si --
verificarlo con aserción explícita.
```

---

### TAREA 3.5 -- Verificacion de pertenencia via asset_index

**Estado:** ✅ COMPLETADA | **Rama:** feat/core-asset-index-authorization | **Depende de:** nada

> Cerrada con evidencia: `AssetAuthorizationPort` (interfaz) + `AssetAuthorizationService`, 4 tests (incluye caso de parámetros nulos). 104 tests en `core`, reactor completo en verde.

```
TAREA: Implementar verificacion de que un assetRef pertenece al fundId
autorizado (previene IDOR, plan-detallado-fase3.md Bloque 4.2).

ENTREGABLES:
1. Metodo assetBelongsToFund(String assetId, String fundId) en
   core.application que consulta asset_index (Tarea 10/ADR-011) y compara
   projectionId contra fundId.
2. Recibe assetId interno ya resuelto, NO assetRef -- la resolucion
   assetRef->assetId es responsabilidad separada (Tarea 3.7).

DEFINITION OF DONE: tests: assetId del fundId correcto -> true; de otro
fundId -> false; inexistente en asset_index -> false (nunca excepcion).
Output literal de Surefire de mvn test -pl core completo.
```

---

### TAREA 3.6 -- Coleccion location_reference

**Estado:** ✅ COMPLETADA | **Rama:** feat/core-location-reference | **Depende de:** nada

> Cerrada con evidencia: test de integración real con Testcontainers (no mock) confirmando consulta exacta y case-sensitive, sin heurística. `.gitignore` corregido de paso (hallazgo real, `target/` no estaba ignorado).

```
TAREA: Tabla de referencia determinista de ubicaciones a zona/ciudad
(plan-detallado-fase3.md Bloque 1.1 -- MongoDB, nunca heuristica de texto).

ENTREGABLES:
1. LocationReferenceDocument: clave (rawLocation, tal como aparece en
   currentLocation/currentCustodian del dominio) -> city/zoneName.
   Coleccion location_reference.
2. LocationReferenceService.resolveZone(String rawLocation) ->
   Optional<String> -- consulta EXACTA, sin fuzzy matching, sin heuristica
   de ningun tipo. Ausencia de entrada -> Optional.empty(), NUNCA un valor
   inventado o un placeholder tipo "UNKNOWN".
3. No pobles la coleccion con datos operativos reales -- puede quedar
   vacia o con 2-3 entradas de prueba solo para los tests. Poblarla es
   trabajo posterior, fuera de este backlog tecnico.

QUE NO HACER: no implementes ningun tipo de matching aproximado, ni
normalizacion de texto (mayusculas/tildes) salvo que decidas que hace
falta para que la consulta exacta funcione de forma predecible -- si lo
haces, justificalo explicitamente como parte de "consulta exacta", no
como heuristica.

DEFINITION OF DONE: tests: ubicacion conocida -> zona correcta; ubicacion
desconocida -> Optional.empty(); ubicacion null (caso DISPATCHED, donde
currentLocation es null en el dominio) -> Optional.empty() sin excepcion.
Output literal de Surefire de mvn test -pl core completo.
```

---

### TAREA 3.7 -- Servicio de calculo de assetRef

**Estado:** ✅ COMPLETADA | **Rama:** feat/core-asset-ref-service | **Depende de:** 3.2

> Cerrada con evidencia: determinismo, ausencia de colisiones, resolución inversa correcta. Limitación conocida documentada (O(N) sobre candidatos de una donación, sin índice persistido). 110 tests en `core`.

```
TAREA: Calculo determinista de assetRef desde assetId (ADR-021-D): HMAC-
SHA-256 completo, dominio "asset-ref:v1:", Base64URL, sin truncar, secreto
DISTINTO del de tracking code.

ENTREGABLES:
1. AssetRefService.computeRef(String assetId) ->
   Base64URL(HMAC-SHA-256("asset-ref:v1:" + assetId)), usando
   assetRefSecret (Tarea 3.2).
2. AssetRefService.resolveAssetId(String assetRef, Collection<String>
   candidateAssetIds) -- dado que el HMAC no es invertible, compara contra
   un conjunto de candidatos conocidos (ej. los assetId de un fundId ya
   resueltos via la proyeccion) y encuentra cual produce ese assetRef al
   recalcularlo. Si consideras que hay un diseno mejor dado que no existe
   un indice assetRef->assetId por diseno, reportalo con tu justificacion
   antes de implementar el enfoque por defecto.

DEFINITION OF DONE: tests: determinismo (mismo assetId -> mismo assetRef
siempre); dos assetId distintos no colisionan en una muestra razonable;
resolucion inversa correcta dado el conjunto de candidatos correcto.
Output literal de Surefire de mvn test -pl core completo.
```

---

### TAREA 3.8 -- Mapper (DonationReadModel -> PublicDonationTrackingDTO)

**Estado:** ✅ COMPLETADA | **Rama:** feat/api-donation-mapper | **Depende de:** 3.1, 3.6, 3.7

> Cerrada con evidencia: `PublicDonationMapperTest` (7 tests) cubriendo las 5 categorías de custodio (incluye `UNCATEGORIZED` explícito, sin `null` silencioso), legacy null, ubicación conocida/desconocida. Decisión de mapeo de custodio resuelta por `lifecycleStatus`, no por coincidencia de string (que habría fallado contra datos reales).

```
TAREA: Mapper de DonationReadModel a PublicDonationTrackingDTO aplicando
el perimetro de exposicion completo (estado-fase3.md seccion 4).

ENTREGABLES -- records exactos, copialos tal cual, sin narrativeSummary
(retirado tras arbitraje de ADR-024):

record PublicDonationTrackingDTO(
    PublicFinancialSnapshotDTO financialSnapshot, String campaignRef,
    List<PublicLogisticsItemDTO> logistics, PublicDonationStatus status)
record PublicFinancialSnapshotDTO(String currency, long originalAmount,
    long clearedAmount, long pendingAllocationAmount,
    long confirmedAllocationAmount, long refundedAmount)
enum PublicDonationStatus { ACTIVA, EN_PROCESO }
record PublicLogisticsItemDTO(String assetRef, String lifecycleStatus,
    String assetType, String unitOfMeasure, BigDecimal quantity,
    String locationZone, PublicCustodianCategory custodianCategory)
enum PublicCustodianCategory { LOGISTICS_PARTNER, REGIONAL_WAREHOUSE,
    LAST_MILE_CARRIER, LOCAL_ALLY }

NO incluyas PublicVendorCategory en ningun record todavia.

LOGICA:
1. currency/campaignRef: pasan tal cual (nullable) -- @JsonInclude(NON_NULL)
   para omitir del JSON si null, nunca placeholder de texto.
2. status: "ACTIVE"->ACTIVA, "PAUSED"->EN_PROCESO. Valor no esperado ->
   DETENTE y reportalo, no asumas default silencioso.
3. Por LogisticsReadItem: assetId->assetRef (AssetRefService, Tarea 3.7);
   currentLocation->locationZone (LocationReferenceService, Tarea 3.6);
   currentCustodian->PublicCustodianCategory (define la tabla de mapeo; si
   un valor no mapea claramente a ninguna categoria, DETENTE, no inventes
   "OTHER" sin autorizacion); quantity pasa tal cual con
   @JsonFormat(shape=STRING) ya en el record.

DEFINITION OF DONE: test unitario del mapper cubriendo: legacy null, cada
categoria de custodio, ubicacion conocida y desconocida, ambos status.
Output literal de Surefire de mvn test -pl core,api completo.
```

---

### TAREA 3.9 -- Primer controlador REST

**Estado:** ✅ COMPLETADA | **Rama:** feat/api-donation-tracking-controller | **Depende de:** 3.0, 3.4, 3.8

> Cerrada con evidencia: ruta corregida a `/api/v1/donations/tracking` (sin path param, token vía header). Test end-to-end añadido cubriendo filtro real + controlador real juntos (el `@WebMvcTest` inicial excluía el filtro, hueco detectado y cerrado). `Optional.empty()` → 404 (consistencia eventual, token ya garantizado válido).

```
TAREA: GET /api/v1/donations/tracking/{trackingCode}.

DETALLES:
1. El filtro (Tarea 3.4) ya valido el token -- el controlador recibe el
   fundId ya resuelto, no revalida.
2. Invoca DonationReadPort.findByFundId(fundId). Si Optional.empty():
   404 -- pero evalua y reporta si esto puede ocurrir en la practica dado
   que el filtro ya garantizo un token criptograficamente valido.
3. Mapea (Tarea 3.8) y responde 200 con el DTO.

DEFINITION OF DONE: test de integracion end-to-end (MockMvc/WebTestClient
+ Testcontainers): token valido con fondo existente -> 200 correcto; los
casos de autorizacion de Tarea 3.4 siguen funcionando end-to-end a traves
de este controlador real. Output literal de Surefire de
mvn test -pl core,api completo.
```

---

### TAREA 3.10 -- Segundo endpoint (historial de activo)

**Estado:** ✅ COMPLETADA | **Rama:** feat/api-asset-history-endpoint | **Depende de:** 3.5, 3.7, 3.9

> Cerrada con evidencia: 401 uniforme (no 404) para "sin proyección" y "assetRef no pertenece", con justificación explícita de por qué difiere del 404 de Tarea 3.9 (aquí hay superficie de enumeración vía `assetRef`, ahí no). Read Model nuevo (`AssetHistoryReadModel`) evitó una violación real de ArchUnit (`api` dependiendo de `core.infrastructure` a través de `AssetHistoryProjectionDocument`).

```
TAREA: GET /api/v1/donations/tracking/{trackingCode}/assets/{assetRef}/history.

ENTREGABLES:
1. AssetHistoryPublicDTO y PublicTransitionDTO -- mismo perimetro de
   ADR-021-D usado para PublicLogisticsItemDTO (sequence excluido,
   location->locationZone, custodian->categoria, eventType/status/timestamp
   exponibles tal cual).
2. Flujo: filtro ya valido token->fundId; resuelve assetRef->assetId (Tarea
   3.7, candidatos = assetId del fundId, via DonationReadModel.logistics);
   si no resuelve -> 401 (mismo formato que token invalido, no 404 --
   evita revelar si el assetRef es valido de otro fondo); si resuelve,
   verifica ademas con assetBelongsToFund (Tarea 3.5) como defensa en
   profundidad -- decide si consolidar ambas verificaciones o mantenerlas
   separadas, justifica; consulta historial via un puerto de lectura
   nuevo analogo a DonationReadPort (crealo, mismo patron de Tarea 3.1).

DEFINITION OF DONE: tests: assetRef valido del fondo -> 200 con historial;
assetRef valido pero de otro fondo -> 401 (mismo body); assetRef invalido
-> 401. Output literal de Surefire de mvn test -pl core,api completo.
```

---

### TAREA 3.11 -- NarrativeReadPort en contracts

**Estado:** ✅ COMPLETADA | **Rama:** feat/contracts-narrative-read-port

```
TAREA: Contrato neutral NarrativeReadPort en contracts (ADR-024 enmendado).

CONTEXTO YA CONGELADO:
- Vive en contracts, NO en core.application.port.out -- cruza hacia ai.
- "Lectura" que puede disparar generacion lazy asincrona como efecto
  colateral documentado -- ya evaluado y aceptado, no lo cuestiones.
- Estados externos: SOLO AVAILABLE y PENDING. No FAILED/NOT_APPLICABLE/STALE.
- Completamente neutral: nada de CacheKey, CompletableFuture,
  NarrativeCacheCoordinator, MongoDB, DonorReportDTO.

ENTREGABLES:
1. NarrativeReadPort: Optional<NarrativeReadModel>
   getOrTriggerGeneration(String fundId); -- nombre elegido para que el
   efecto colateral sea visible en la firma.
2. NarrativeReadModel: status (NarrativeStatus: AVAILABLE, PENDING),
   content (nullable, null si PENDING), source (NarrativeSource:
   LLM_GENERATED, FALLBACK_TEMPLATE -- null si PENDING).
3. Sin imports de Spring/Mongo/concurrencia.

DEFINITION OF DONE: contracts compila aislado, sin dependencia externa
mas que el JDK. No requiere test dedicado (solo tipos, sin
comportamiento) -- confirmarlo explicitamente si es el caso.
```

---

### TAREA 3.12 -- Implementacion de NarrativeReadPort dentro de ai

**Estado:** ✅ COMPLETADA | **Rama:** feat/ai-narrative-port-adapter | **Depende de:** 3.11 (mergeada a develop)

> Cerrada con evidencia: `AuditFactsNotYetAvailableException` nombrada en vez de reutilizar `IllegalArgumentException` genérica. Single-flight reutilizado sin `.join()`. 19 tests en `ai`, ninguna de las 9 llamadas legacy de `DonorReportGeneratorTest` rota.

```
TAREA: Implementar NarrativeReadPort dentro de ai, adaptando
DonorReportGenerator/NarrativeCacheCoordinator (Tarea 12 Fase 2) al
contrato asincrono de ADR-024.

CONTEXTO -- verifica primero, no asumas:
1. Lee el estado ACTUAL de DonorReportGenerator y NarrativeCacheCoordinator
   -- cita codigo real. El metodo generate() actual termina en .join(),
   prohibido en el camino expuesto a HTTP por ADR-024.
2. El single-flight (ConcurrentHashMap<CacheKey, CompletableFuture>) NO se
   elimina, se reutiliza -- el cambio es que quien invoca desde fuera de ai
   ya no espera (.join()), lo consulta sin bloquear.

ENTREGABLES:
1. getOrTriggerGeneration(fundId):
   a. Si existe DonorReportDocument vigente -> AVAILABLE, content, source
      mapeado desde modelIdentifier ("FALLBACK" ya usado como sentinel).
   b. Si no existe o no vigente -> dispara generacion via single-flight
      existente (sin duplicar si ya hay una en curso), NO espera (no
      .join()) -> retorna inmediatamente PENDING, content=null, source=null.
2. Confirma si el mapeo fundId->AuditFactsDTO sigue igual que en Tarea 12.

QUE NO HACER: no reescribas el single-flight. No agregues campos a
DonorReportDTO. No dejes ningun camino bloqueante.

DEFINITION OF DONE: tests: sin narrativa previa -> PENDING inmediato, sin
bloquear (verificar tiempo de respuesta acotado, ej. <100ms aunque el LLM
mockeado tarde segundos); con narrativa LLM -> AVAILABLE/LLM_GENERATED; con
fallback vigente -> AVAILABLE/FALLBACK_TEMPLATE; dos invocaciones
concurrentes sin narrativa previa -> una sola ejecucion real (single-flight
verificado en este nuevo camino). Output literal de Surefire de
mvn test -pl ai completo.
```

---

### TAREA 3.13 -- Endpoint HTTP de narrativa

**Estado:** ✅ COMPLETADA | **Rama:** feat/api-narrative-endpoint | **Depende de:** 3.0, 3.4, 3.12

> Cerrada con evidencia: `PublicNarrativeControllerIntegrationTest` (4 tests) y `PublicNarrativeMapperTest` (3 tests) citados por nombre. 202 PENDING / 200 AVAILABLE probados por separado del caso 404 (sin proyección). Corregido un problema de proceso: el commit/merge de Tarea 3.10 se había quedado pendiente mientras se avanzaba a 3.13 en la misma sesión — resuelto separando ambos en commits y merges independientes antes de cerrar.

```
TAREA: GET /api/v1/donations/tracking/{trackingCode}/narrative.

DETALLES:
1. El filtro (Tarea 3.4) ya protege /tracking/** -- confirma que esta ruta
   cae dentro sin configuracion adicional (si no, reportalo).
2. Controlador recibe fundId ya resuelto (mismo mecanismo de Tarea 3.9).
3. Invoca NarrativeReadPort.getOrTriggerGeneration(fundId).
4. Mapea a NarrativeResponseDTO (status, content, source --
   estado-fase3.md seccion 4).
5. PENDING -> 202 Accepted; AVAILABLE -> 200 OK.
6. Endpoint independiente del de Tarea 3.9 -- NO modifiques
   PublicDonationTrackingDTO ni su controlador (decision ya revertida).

QUE NO HACER: no reintroduzcas narrativa a PublicDonationTrackingDTO. No
hagas que este endpoint bloquee esperando al LLM.

DEFINITION OF DONE: tests end-to-end: primera consulta sin narrativa
previa -> 202 PENDING; segunda tras esperar generacion (poll con timeout
razonable) -> 200 AVAILABLE; token invalido -> 401 (mismo formato que los
otros endpoints); trackingCode valido pero fundId sin proyeccion -> definir
y probar el comportamiento con justificacion (caso no cubierto en el
arbitraje). Output literal de Surefire de mvn test -pl ai,api completo.
```

---

## 5. Orden de ejecucion — CIERRE COMPLETO (7 de septiembre de 2026)

Las 14 tareas del backlog (3.0 a 3.13) estan **COMPLETADAS**, verificadas con evidencia literal (Surefire, reactor completo, `git status`) y mergeadas a `develop` en el orden correcto:

```
BLOQUE 1 (sin dependencias entre si):
  3.2  (secretos)                    OK
  3.11 (NarrativeReadPort/contracts) OK
  3.3  (tracking code service)       OK
  3.6  (location_reference)          OK
  3.0  (api scaffolding)             OK
  3.5  (asset_index auth)            OK
  3.1  (DonationReadPort)            OK

CADENA SECUENCIAL:
  3.12 (ai implementa NarrativeReadPort) OK
  3.4  (filtro auth)                     OK
  3.7  (assetRef service)                OK
  3.8  (mapper)                          OK
  3.9  (primer controlador)              OK
  3.10 (segundo endpoint)                OK
  3.13 (endpoint de narrativa)           OK
```

**Los tres endpoints públicos de Fase 3 están completos y verificados end-to-end:**
- `GET /api/v1/donations/tracking` — resumen de donación (200 / 401 / 404 consistencia eventual).
- `GET /api/v1/donations/tracking/assets/{assetRef}/history` — historial de activo (200 / 401 uniforme).
- `GET /api/v1/donations/tracking/narrative` — narrativa asíncrona (202 PENDING / 200 AVAILABLE / 401).

**Verificación final de `git log --oneline` en `develop`** (orden de merges, confirmado):
```
35aebd5 Merge branch 'feat/api-narrative-endpoint' into develop (Task 3.13)
208d590 feat(api): implement public narrative endpoint (Task 3.13)
df4a307 Merge branch 'feat/api-asset-history-endpoint' into develop (Task 3.10)
e4ebb8b feat(api): implement public asset history endpoint (Task 3.10)
52fba42 test(api): update E2E integration test to cover all required cases for Task 3.9
```
(y los merges anteriores de 3.0–3.9, 3.11, 3.12 en la misma secuencia, cada uno con su propio commit y merge `--no-ff`).

### Lección de proceso registrada durante esta fase

Dos veces durante la ejecución (Tarea 3.5 y, más notablemente, Tarea 3.10/3.13) el **contenido** de una tarea fue aprobado en revisión pero el paso mecánico de `git commit`/`merge` a `develop` no se ejecutó de inmediato, permitiendo que el trabajo de la siguiente tarea se mezclara sin comitear en la misma rama. Ambos casos se detectaron por auditoría explícita del `git log`/`git status` antes de aceptar el cierre, nunca por confianza en el resumen narrativo. **Recomendación para fases futuras:** cada entrega de cierre de tarea debe incluir el `git log --oneline -3` posterior al merge como parte obligatoria de la evidencia, no solo cuando se solicita explícitamente.

### Deuda técnica y decisiones abiertas heredadas a la siguiente fase

- `PublicVendorCategory` definido pero nunca incluido en ningún DTO — sigue sin resolverse si se expone un resumen de `allocations[]`.
- Población real de la colección `location_reference` con datos operativos — quedó vacía/con datos de prueba, es trabajo operativo posterior, no de diseño.
- Rotación y gestión definitiva de los dos secretos HMAC (tracking code, assetRef) en un entorno de despliegue real — se definieron como variables de entorno, sin vault dedicado (decisión consciente, ver `estado-fase3.md`).
- Endpoint/mecanismo administrativo para invocar `TrackingCodeService.revoke()` — el método existe y está probado, pero no hay ninguna vía operativa (JMX u otra) para invocarlo en producción, análogo a como se resolvió `resolveStuckBatch` en ADR-022.
