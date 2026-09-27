# Hallazgo Transversal: Pérdida silenciosa de eventos en Projection Retry Framework

## 1. Alcance
Defecto transversal arquitectónico en el mecanismo compartido de reintento (retry) de proyecciones (CQRS Read Model).

## 2. Componentes Afectados
* `DonationProjectionHandler`
* `DonationAuditFactsHandler`
* `PendingAllocationProjectionHandler`
* `ProjectionEventSource`
* `ProjectionRetryScheduler`

## 3. Evidencia
El mecanismo actual de recuperación ante fallos de proyección tiene una contradicción de diseño que resulta en la eliminación inadvertida de eventos fallidos:

1. **Captura en Handlers:** Los tres handlers de proyección (`DonationProjectionHandler`, `DonationAuditFactsHandler`, `PendingAllocationProjectionHandler`) atrapan explícitamente excepciones (`SequenceGapException`, `MissingDependencyException`) dentro de su método `handleEvent()`. En el bloque `catch`, invocan a `enqueueForRetry()` que guarda o sobrescribe un `ProjectionRetryDocument` con `retryCount = 0` y estado `PENDING`.
2. **EventSource Continúa:** `ProjectionEventSource` invoca a los handlers dentro de un bloque `try-catch`. Como los handlers atrapan internamente sus excepciones predecibles, el Source asume éxito, hace log de cualquier otra excepción no controlada, y siempre avanza el checkpoint de Mongo Change Streams.
3. **Scheduler Reprocesa:** Asincrónicamente, `ProjectionRetryScheduler` recoge los documentos `PENDING` e invoca `handler.handleEvent(eventDoc)`.
4. **El Defecto:** Si durante el reprocesamiento por parte del Scheduler el evento vuelve a fallar, el handler **vuelve a atrapar la excepción** y sobrescribe el documento en MongoDB (reiniciando `retryCount` y `firstAttemptAt`). Como el handler finaliza sin propagar la excepción al Scheduler, este último asume que el procesamiento fue un éxito y ejecuta `retryRepository.delete(retryDoc)`, borrando físicamente el evento.
5. **Código Muerto:** El Scheduler posee un bloque `catch (SequenceGapException | MissingDependencyException e)` destinado a incrementar `retryCount` y poner el evento en cuarentena (QUARANTINED) a las 4 horas, pero este bloque es inalcanzable dado que las excepciones jamás escapan de `handleEvent()`.

## 4. Impacto
Riesgo de **pérdida silenciosa de datos (Data Loss)**. Si un evento entra en la colección de reintentos y vuelve a fallar cuando el Scheduler lo procesa, el evento se borra permanentemente de la base de datos sin entrar a cuarentena y sin alertar del fallo, provocando inconsistencia irreversible entre el Write Model (Event Store) y el Read Model.

## 5. Estado
**ABIERTO**

## 6. Alcance (NUEVA-4)
**FUERA DE NUEVA-4.** Este hallazgo es un defecto base del framework compartido de CQRS y afecta a todos los handlers, no es específico de la funcionalidad de `PENDING_ALLOCATION`.

## 7. Próximo Paso
Requiere decisión arquitectónica (posiblemente un ADR propio) para corregir el contrato entre los handlers, el `ProjectionEventSource` y el `ProjectionRetryScheduler` antes de modificar el mecanismo compartido. No se realizará ninguna modificación al framework bajo el alcance de NUEVA-4.
