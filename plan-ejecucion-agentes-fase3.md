# Plan de Ejecución para Agentes de Código — Fase 3: Implementación

**Proyecto:** Motor de Trazabilidad Verificable de Donaciones (nombre comercial provisional PaxFide, no usar en código: package base `com.traceability`)
**Fase:** 3 — Exposición REST de Lectura (Fase 2 formalmente cerrada, incluyendo auditoría y sus 13 hallazgos)
**Diseño de referencia:** `estado-fase3.md` (contratos congelados, ahora con 3 endpoints tras el arbitraje de ADR-024) y `plan-detallado-fase3.md`
**Audiencia:** agentes de código autónomos y los ingenieros que los supervisan.

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

REGLA DE ORO (sin cambios): no eres un generador de código que maximiza
líneas escritas. Eres un implementador disciplinado de un contrato ya
cerrado. Si no puedes señalar el ADR o la sección de `estado-fase3.md`/
`plan-detallado-fase3.md` que justifica una decisión, DETENTE y pregunta.

CATÁLOGO DE ADRs DE FASE 3:

- ADR-020: Módulo `api` nuevo. app -> api -> core (solo puertos de
  aplicación). api NUNCA importa core.infrastructure.* directamente.
- ADR-021-A/B/C/D: Tracking code bearer, stateless, HMAC-SHA-256 con
  dominio versionado "tracking:v1:", MessageDigest.isEqual() para
  comparación, expiración embebida, revocación dispersa con TTL,
  generación FUERA de Fase 3, perímetro de exposición ya clasificado.
- ADR-023: Sin Spring Security. OncePerRequestFilter propio.
- ADR-024 (Approved with amendment): Narrativa de IA expuesta como
  ENDPOINT INDEPENDIENTE, nunca embebida en el DTO principal. Ver sección
  3 y 4 de estado-fase3.md. NarrativeReadPort vive en `contracts` (única
  excepción a la regla general de que los puertos de api->core viven en
  core.application.port.out — aquí SÍ va en contracts porque el puerto
  cruza hacia `ai`, que nunca puede depender de `core` ni de `api`).

REGLAS DE CÓDIGO NO NEGOCIABLES:
1. api.* nunca contiene lógica de negocio, nunca accede a MongoDB
   directamente, nunca importa core.infrastructure.projection.mongo.
   documents.* ni ninguna clase interna de `ai` (DonorReportDTO, CacheKey,
   NarrativeCacheCoordinator quedan siempre dentro de `ai`).
2. DTOs públicos son records inmutables. Nunca se expone
   DonationProjectionDocument, DonationReadModel, ni DonorReportDTO
   directamente como respuesta HTTP.
3. Dos secretos HMAC distintos (tracking code, assetRef) — nunca el mismo
   valor, nunca compartidos entre sí.
4. Constructor injection siempre.
5. Toda excepción de autorización responde con el MISMO código HTTP 401 y
   el mismo formato de error — nunca reveles la causa específica.
6. El recurso principal de tracking (GET /tracking/{code}) SIEMPRE responde
   200 si el token es válido, independientemente del estado de cualquier
   otro subsistema (incluida la narrativa, que ya no vive ahí).

SI DETECTAS UNA CONTRADICCIÓN, AMBIGÜEDAD, O CASO NO CUBIERTO: DETENTE.
Documenta el conflicto exacto y espera instrucción.
```

---

## 2. Reglas de Ejecución (idénticas a Fase 2 y a la versión anterior de este plan)

Sin cambios: DoD de siete condiciones, tests de autorización con MockMvc/WebTestClient reales, nunca solo el método Java en aislamiento.

---

## 3. Estrategia de Ramas

Sin cambios respecto a la versión anterior — partir de `develop`, una rama por tarea, PR individual con aprobación humana. Nomenclatura ampliada para las tareas nuevas de esta revisión:

| Tarea | Rama sugerida |
|---|---|
| 3.0 – 3.10 | (sin cambios respecto al plan anterior) |
| 3.11 | `feat/contracts-narrative-read-port` |
| 3.12 | `feat/ai-narrative-port-adapter` |
| 3.13 | `feat/api-narrative-endpoint` |

---

## 4. Backlog Secuencial de Tareas

*(Tareas 3.0 a 3.10 sin cambios respecto a la versión anterior de este documento — scaffolding de `api`, `DonationReadPort`, secretos, tracking code service, filtro de autorización, `asset_index` de autorización, `location_reference`, `assetRef` service, mapper, primer controlador, segundo endpoint. Reutilizar tal cual.)*

### TAREA 3.11 — `NarrativeReadPort` en `contracts` (ADR-024 enmendado)

**Estado:** 🔲 Pendiente — YA NO requiere Modo de Arquitectura adicional, el arbitraje resolvió el diseño completo.
**Rama:** `feat/contracts-narrative-read-port`
**Depende de:** ninguna tarea de `api` — puede empezar en paralelo con cualquier tarea de la sección anterior.

```
TAREA: Definir el contrato neutral NarrativeReadPort en el módulo `contracts`,
según ADR-024 enmendado.

CONTEXTO YA CONGELADO — no lo reinterpretes:
- Este puerto vive en `contracts`, NO en core.application.port.out (a
  diferencia de DonationReadPort de Tarea 3.1) — porque este puerto cruza
  hacia el módulo `ai`, que nunca puede depender de `core` ni de `api`
  (regla vigente desde Fase 1). `contracts` es el único módulo del que
  `ai` puede depender, análogo al patrón ya usado por AuditFactsPort.
- El puerto es "de lectura" mencionado como tal, pero puede disparar
  generación lazy asíncrona como efecto colateral documentado — decisión
  ya evaluada y aceptada en el arbitraje de ADR-024, no la cuestiones.
- Estados externos: SOLO AVAILABLE y PENDING. No agregues FAILED,
  NOT_APPLICABLE, ni STALE — no hay evidencia de que sean estados reales
  del modelo actual (ya evaluado explícitamente y descartado).
- El contrato de contracts es completamente neutral: no debe conocer
  CacheKey, CompletableFuture, NarrativeCacheCoordinator, MongoDB, ni
  DonorReportDTO — todo eso permanece encapsulado dentro de `ai`.

ENTREGABLES (módulo `contracts`):
1. NarrativeReadPort (interfaz):
   Optional<NarrativeReadModel> getOrTriggerGeneration(String fundId);
   (nombre del método elegido deliberadamente para que el efecto colateral
   de disparar generación sea visible en la firma, no solo documentado en
   Javadoc aparte — si prefieres otro nombre igual de explícito, justifica
   el cambio, pero NO uses un nombre genérico tipo "get" o "find" que
   sugiera lectura pura sin efectos.)
2. NarrativeReadModel (record): status (enum NarrativeStatus:
   AVAILABLE, PENDING), content (String, nullable — null si PENDING),
   source (enum NarrativeSource: LLM_GENERATED, FALLBACK_TEMPLATE —
   null si PENDING).
3. Ningún import de Spring, MongoDB, ni tipos concurrentes
   (CompletableFuture, ConcurrentHashMap) en este módulo — sigue la misma
   regla de pureza que ya rige `contracts` desde Fase 1 (Tarea 2 del plan
   de Fase 2).

QUÉ NO HACER:
- No implementes el puerto todavía (eso es Tarea 3.12, dentro de `ai`).
- No agregues ningún campo o estado no listado arriba.

DEFINITION OF DONE: el módulo `contracts` compila de forma aislada (sin
ninguna dependencia externa más que el JDK), igual que se validó en la
Tarea 2 original de Fase 2.
```

---

### TAREA 3.12 — Implementación de `NarrativeReadPort` dentro de `ai`

**Estado:** 🔲 Pendiente
**Rama:** `feat/ai-narrative-port-adapter`
**Depende de:** 3.11.

```
TAREA: Implementar NarrativeReadPort (Tarea 3.11) dentro del módulo `ai`,
adaptando el DonorReportGenerator/NarrativeCacheCoordinator ya existentes
(Tarea 12 de Fase 2) al contrato asíncrono de ADR-024.

CONTEXTO — verifica primero, no asumas:
1. Lee el estado ACTUAL de DonorReportGenerator y NarrativeCacheCoordinator
   tal como quedaron tras Tarea 12 — cita el código real antes de
   modificarlo. El método generate() actual termina en .join(), lo cual
   ADR-024 prohíbe explícitamente para el camino expuesto a HTTP.
2. Confirma que el single-flight (ConcurrentHashMap<CacheKey,
   CompletableFuture<DonorReportDTO>>) sigue intacto — este mecanismo NO
   se elimina, se reutiliza. El cambio es que quien invoca desde fuera
   de `ai` ya no espera (.join()) ese CompletableFuture — lo consulta sin
   bloquear.

ENTREGABLES:
1. Implementación de NarrativeReadPort.getOrTriggerGeneration(fundId):
   a. Verifica si ya existe un DonorReportDocument persistido y vigente
      (no expirado según nextRetryAt, si aplica al camino de fallback) para
      ese fundId. Si existe: retorna NarrativeReadModel con status=
      AVAILABLE, content, source mapeado (LLM_GENERATED/FALLBACK_TEMPLATE)
      desde el modelIdentifier ya existente en DonorReportDTO (recuerda:
      "FALLBACK" es el sentinel ya usado, nunca null).
   b. Si no existe o no está vigente: dispara la generación vía el
      mecanismo single-flight YA EXISTENTE (sin duplicar llamadas al LLM
      si ya hay una generación en curso para ese fundId), pero NO espera
      el resultado (no .join()) — retorna inmediatamente
      NarrativeReadModel con status=PENDING, content=null, source=null.
2. Confirma explícitamente en tu resumen: ¿el mapeo de fundId a
   AuditFactsDTO (lo que el generador necesita como entrada) sigue
   funcionando igual que en Tarea 12, o requiere algún ajuste?

QUÉ NO HACER:
- No elimines ni reescribas el mecanismo de single-flight — se reutiliza.
- No agregues ningún campo nuevo a DonorReportDTO — ese tipo permanece
  interno de `ai`, sin cambios.
- No dejes ningún camino donde el método bloquee esperando al LLM.

DEFINITION OF DONE: tests unitarios/integración: fundId sin narrativa
previa → PENDING inmediato, sin bloquear el hilo llamador (verificar con
un test de tiempo de respuesta acotado, ej. debe retornar en menos de
100ms incluso si el LLM mockeado tarda segundos); fundId con narrativa ya
generada (LLM) → AVAILABLE con source=LLM_GENERATED; fundId con fallback
vigente → AVAILABLE con source=FALLBACK_TEMPLATE; dos invocaciones
concurrentes para el mismo fundId sin narrativa previa → una sola
ejecución real del generador (verificar que el single-flight sigue
funcionando en este nuevo camino de invocación).
```

---

### TAREA 3.13 — Endpoint HTTP de narrativa

**Estado:** 🔲 Pendiente
**Rama:** `feat/api-narrative-endpoint`
**Depende de:** 3.0, 3.4 (filtro de autorización), 3.12.

```
TAREA: Implementar GET /api/v1/donations/tracking/{trackingCode}/narrative.

DETALLES:
1. El filtro de Tarea 3.4 ya protege el patrón /tracking/** — confirma que
   esta ruta nueva cae dentro de ese patrón sin necesitar configuración
   adicional del filtro (si no es así, repórtalo, no lo asumas).
2. El controlador recibe el fundId ya resuelto por el filtro (igual que el
   controlador de Tarea 3.9 — mismo mecanismo de paso de fundId, no
   inventes uno nuevo).
3. Invoca NarrativeReadPort.getOrTriggerGeneration(fundId).
4. Mapea NarrativeReadModel a NarrativeResponseDTO (record público, ver
   estado-fase3.md sección 4 — campos: status, content, source).
5. Códigos de respuesta:
   - status == PENDING → HTTP 202 Accepted, body con status=PENDING,
     content y source ausentes/null.
   - status == AVAILABLE → HTTP 200 OK, body completo.
6. Este endpoint es independiente del controlador de Tarea 3.9 — NO
   modifiques PublicDonationTrackingDTO ni el controlador principal para
   esta tarea. Son recursos separados por diseño (ADR-024 enmendado).

QUÉ NO HACER:
- No agregues ningún campo de narrativa de vuelta a
  PublicDonationTrackingDTO — esa decisión ya se revirtió explícitamente.
- No hagas que este endpoint bloquee esperando al LLM — el puerto de
  Tarea 3.12 ya garantiza que no bloquea, no reintroduzcas espera aquí.

DEFINITION OF DONE: tests de integración end-to-end (MockMvc/WebTestClient
+ Testcontainers, con el LLM mockeado o con fallback forzado si no hay
proveedor real en el entorno de test): primera consulta sin narrativa
previa → 202 PENDING; segunda consulta tras esperar la generación (poll
manual en el test, con un timeout razonable) → 200 AVAILABLE; token
inválido → 401 (mismo formato que los otros dos endpoints); trackingCode
válido pero fundId sin proyección asociada → definir y probar el
comportamiento (¿404? ¿PENDING igual? — decide con justificación, dado
que este caso no fue explícitamente cubierto en el arbitraje).
```

---

## 5. Orden de ejecución actualizado

```
[Bloque en paralelo, sin dependencias entre sí]
Tarea 3.0 (api scaffolding)
Tarea 3.1 (DonationReadPort)
Tarea 3.2 (secretos)
Tarea 3.5 (asset_index auth)
Tarea 3.6 (location_reference)
Tarea 3.11 (NarrativeReadPort en contracts)  ← NUEVO, sin dependencias
        │
        ▼ [APROBACIÓN cada una]
Tarea 3.3 (tracking code service) ──► [APROBACIÓN]
Tarea 3.12 (ai implementa NarrativeReadPort) ──► [APROBACIÓN]  ← NUEVO
        │
        ▼
Tarea 3.4 (filtro auth) ──► [APROBACIÓN]
Tarea 3.7 (assetRef service) ──► [APROBACIÓN]
        │
        ▼
Tarea 3.8 (mapper) ──► [APROBACIÓN]
        │
        ▼
Tarea 3.9 (primer controlador) ──► [APROBACIÓN]
        │
        ▼
Tarea 3.10 (segundo endpoint) ──► [APROBACIÓN]
Tarea 3.13 (endpoint de narrativa) ──► [APROBACIÓN]  ← NUEVO, en paralelo con 3.10
```

Nota: Tarea 3.13 depende de 3.4 y 3.12, no de 3.9/3.10 — puede implementarse en paralelo con el segundo endpoint si hay dos personas/agentes disponibles.
