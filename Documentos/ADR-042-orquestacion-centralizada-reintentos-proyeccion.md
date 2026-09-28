# ADR-042 — Orquestación Centralizada de Reintentos de Proyección (A7.2)

## Estado
Aprobado — implementado en Fase 5 (A7.2). Refina ADR-010 (cuarentena de eventos fuera de orden) y ADR-017 (framework de proyección genérico); no los sustituye.

*Nota de numeración:* se usa ADR-042 porque ADR-033 a ADR-037 ya tienen colisiones entre Fase 5 y Fase 6, ADR-038/ADR-039 existen solo en `develop` local (no publicados) y `plan-correccion-fase5-e-ia.md` reserva ADR-037 a ADR-041 como candidatos para renumerar los ADR de Fase 6.

## Contexto
ADR-010 fija la política de orden de la capa de lectura: un evento que no puede proyectarse queda pendiente de reintento y, pasadas 4 horas, se pone en cuarentena, se pausa la proyección afectada sin bloquear otros streams y se reanuda manualmente con `resumeProjection`, en orden estricto de sequence. ADR-017 hace genéricos `ProjectionEventSource` y `ProjectionRetryScheduler`, enrutando reintentos por `handlerName`.

Hasta A7.2 la responsabilidad de reintentar estaba repartida: cada `ProjectionEventHandler` capturaba sus propias excepciones y encolaba su `ProjectionRetryDocument`, con criterios distintos entre handlers.

## Problema
1. Clasificación inconsistente: cada handler decidía qué error era reintentable y cuál no, y algunos errores se tragaban sin quedar registrados.
2. El checkpoint del change stream podía avanzar sin que existiera un registro durable del evento fallido, perdiéndolo.
3. `ProjectionRetryScheduler` liberaba un fallo reintentable a `PENDING` dentro del mismo bucle y lo volvía a reclamar de inmediato: miles de intentos por ejecución sin backoff efectivo, acaparando el hilo único del scheduler (saga outbox, anclaje) durante toda la ventana de 4 horas.
4. `resumeProjection` borraba el documento en cuarentena si el reproceso fallaba, confiando en que el handler lo re-encolaría; al centralizar los reintentos esa copia era la única y el evento se perdía, dejando además la proyección `ACTIVE`.

## Decisión
1. **Los handlers no gestionan reintentos.** `ProjectionEventHandler.handleEvent` propaga la excepción; ningún handler escribe `ProjectionRetryDocument`.
2. **Primer intento en `ProjectionEventSource`.** Si un handler falla, el source crea el `ProjectionRetryDocument` (por evento y handler) con `retryCount = 0` y `firstAttemptAt`, y clasifica el error:
   - reintentable (hueco de secuencia, dependencia ausente, errores de acceso a datos / Mongo transitorios) → `PENDING`;
   - permanente (cualquier otro, incluido `ProjectionPausedException`, payload no deserializable o proyección ya `PAUSED`) → `QUARANTINED`.
3. **Checkpoint solo tras registro durable.** El resume token avanza únicamente si todos los handlers tuvieron éxito o su fallo quedó persistido como documento de retry. Si la persistencia del retry falla, el checkpoint no avanza y el evento se volverá a entregar.
4. **Reintentos posteriores solo en `ProjectionRetryScheduler`.** El scheduler es el único que reintenta: éxito → borra el documento; error permanente o handler desconocido → cuarentena; error reintentable → incrementa `retryCount` y `lastAttemptAt`.
5. **Ventana máxima de 4 horas** desde `firstAttemptAt` (ADR-010). Superada, el siguiente fallo reintentable pone el documento en cuarentena y pausa la proyección.
6. **Como máximo un intento por documento y ejecución del scheduler.** Los fallos reintentables permanecen en `PROCESSING` hasta el final de la ejecución y solo entonces vuelven a `PENDING`. El backoff efectivo es el `fixedDelay` del scheduler (constante), no exponencial: se acepta esta desviación respecto a ADR-010 por simplicidad.
7. **`resumeProjection` conserva el evento.** Reactiva la proyección y reprocesa los documentos en cuarentena en orden de sequence; ante el primer fallo, el documento se mantiene `QUARANTINED`, la proyección vuelve a `PAUSED` y el reproceso se detiene para preservar el orden.

## Consecuencias
### Positivas
- Una sola taxonomía de errores y un solo punto de reintento para todos los handlers; los handlers nuevos (p. ej. el de pending allocation, ADR-034) no necesitan lógica de reintento propia.
- Ningún evento fallido se pierde: existe un documento de retry antes de avanzar el checkpoint, y la reanudación manual no destruye la única copia.
- El scheduler ya no puede monopolizar su hilo con un documento que falla en bucle.

### Negativas / Restricciones
- La clasificación reintentable/permanente está duplicada en `ProjectionEventSource` y `ProjectionRetryScheduler` y debe mantenerse sincronizada.
- La cuarentena pausa únicamente proyecciones de `DonationProjection` (resueltas por `projectionId`); los documentos de otros handlers quedan en cuarentena sin pausa de proyección asociada.
- El backoff es lineal (un intento por `fixedDelay`), por lo que en 4 horas el número de intentos depende de la configuración `core.projection.retry.delay`.

## Referencias
- Código: `core.infrastructure.projection.ProjectionEventSource`, `core.application.projection.ProjectionRetryScheduler`, `core.infrastructure.projection.ProjectionEventHandler`.
- Tests:
  - `ProjectionEventSourceTest`: `testCritical_FalloAlPersistirRetry`, `testInitialFailure_PermanentErrorQuarantined_AndCheckpointAdvances`, `testInitialFailure_RetryableErrorPending_AndCheckpointAdvances`.
  - `ProjectionRetrySchedulerTest`: `testFirstAttemptAtAndRetryCount_OnRetryableError`, `testTaxonomy_*`, `testRetryableFailure_AttemptedOncePerRun_AndDoesNotStarveLaterDocuments`, `testRetryableFailureOlderThanFourHours_QuarantinesAndPausesProjection`, `testResumeProjection_FailureKeepsEventQuarantinedAndStops`.
  - `DonationProjectionIntegrationTest`: `testGapAndRetry`, `testStuckProcessingRescue`, `testPauseAndResume`.
- Commits: `e4404e5` (centralización de reintentos, A7.2), `20ba931` (C4: un intento por ejecución, `resumeProjection` sin pérdida del evento, cobertura de la ventana de 4 horas y de la clasificación inicial).
- ADRs: ADR-010, ADR-017 (históricos, recogidos en `documento-maestro-proyecto.md`), ADR-022 (resolución manual vía JMX), ADR-034.
