# ADR-059 — `occurredAt` y `recordedAt`: qué significa cada fecha de un evento y quién la fija

## Status

**PROPUESTO — firma: Pendiente: Carlos.** Número reservado por decisión de Manuel (2026-11-18); el catálogo del documento maestro lo recoge en la Tarea D.

**Es documentación posterior al código.** El mecanismo que fecha hechos en el pasado (`EventTime`, perfil `demo-historico`) entró por #182 (fusionado el 2026-11-16) con un plan aprobado por Manuel (`plan-paso2-datos-historicos-2026-11-16.md`) pero **sin ADR**. Este documento describe lo que el código hace hoy; no cambia código. El visto bueno de Carlos sobre `core` figuraba como pendiente en ese plan: **[Falta: confirmar si Carlos ya lo dio.]**

## Context

Cada `DomainEvent` lleva dos instantes y los dos entran en el mapa canónico y en el hash (`EventCanonicalMapper.toCanonicalMap`, `core/.../application/event/EventCanonicalMapper.java:33`). Hasta #182 no estaba escrito qué significa cada uno.

## Decision

### D1. Significado

| Campo | Significa | Lo fija | Dónde |
|---|---|---|---|
| `occurredAt` | **cuándo ocurrió el hecho** en el dominio | el agregado, al emitir el evento, con `EventTime.now()` | `AggregateRoot.raiseEvent`, `core/.../domain/shared/AggregateRoot.java:43` |
| `recordedAt` | **cuándo lo guardó el sistema** en el event store | el adaptador del event store, con el reloj real | `MongoEventStoreAdapter.java:66` (`Instant.now()`) |

- `recordedAt` **nunca** pasa por `EventTime`: ni la simulación ni nadie lo puede fechar en el pasado. La cadena de hashes y el anclaje dependen de él.
- Un evento rehidratado desde el store (`loadStream`, `MongoEventStoreAdapter.java:120-134`) conserva el `occurredAt` guardado.

### D2. Invariante: `occurredAt ≤ recordedAt`

- Con el reloj real se cumple porque el hecho se emite antes de guardarse.
- Con la fuente simulada se cumple **por construcción**: `EventTime.callAt` rechaza un instante posterior a la hora real (`SimulatedTimeInFutureException`).
- **Antes de #182 no había ninguna comprobación en el código.** Hoy tampoco la hace el event store al guardar. **Pregunta abierta para Carlos:** ¿se comprueba en `MongoEventStoreAdapter` (rechazar el guardado si se incumple)? No se añade sin decisión.

### D3. Quién usa cada campo (estado a `develop` `bcaa5dc`)

- Los read models mensuales y de fondos liberados agrupan por `occurredAt`.
- La actividad y los directorios ordenan por `recordedAt`.
- El verificador de integridad recalcula el hash con ambos (`RecomputingEventIntegrityChecker.java:106-107`) y usa `recordedAt` para decidir si aplica la forma antigua anterior al corte `0579f41` (`:95`).

### D4. Cómo se arma la fuente simulada

`EventTime` (`core/.../domain/shared/EventTime.java`) usa el reloj real salvo dentro de `callAt(instant, action)`. Fechar en el pasado exige las cuatro cosas a la vez:

1. **Armado explícito:** `EventTime.arm()` solo lo llama `DemoHistoricConfig`, que existe solo con el perfil `demo-historico`, y ese perfil exige `dev` (`DemoHistoricProfileNotAllowedException` si no). Sin armar, `callAt` lanza `SimulatedTimeNotEnabledException` y no ejecuta nada. Se arma una sola vez.
2. **Nunca en el futuro** (D2).
3. **Solo en el hilo que lo pide** (`ThreadLocal`): peticiones, planificadores y sagas ven la hora real.
4. **Solo durante el bloque:** al salir, también con excepción, vuelve la hora real; los bloques anidados restauran el exterior.

La regla de arquitectura `SimulatedTimeArchitectureTest` (ArchUnit, en `app`) comprueba que fuera de `bootstrap/historic` nadie llama a `arm` ni a `callAt`.

**Tests de #182** (cifras de la descripción de #182, no re-ejecutadas para este documento): `EventTimeTest` 9, `SimulatedTimeArchitectureTest` 6, `DemoHistoricProfileAbsentTest` 2, `DemoHistoricSeederIntegrationTest` 10. **[Falta: salida literal de Surefire; se adjuntará en el PR de la Tarea D con `ci-local.sh`.]**

## Limitaciones conocidas

- Al dividir un lote, el hijo nace con la fecha real.
- Los pasos hechos **sin conexión** quedan con la hora del servidor al sincronizar, porque las peticiones no llevan hora: `occurredAt` es entonces «cuándo llegó», no «cuándo se hizo».
- `Promt-Contexto.md` describe otra semántica de estos campos; este ADR la sustituye.
- Los javadoc de `DomainEvent`, `EventType`, `DomainEventPayload` y `AggregateRoot` citan ADR-013 por error; **corregirlos es trabajo de código y no se hace aquí.**

## Consequences

- Los datos de demostración con fechas pasadas tienen `occurredAt` pasado y `recordedAt` real (el día de la siembra); la cadena de hashes sigue verificándose.
- Cambiar quién fija cada campo, o añadir la comprobación de D2, requiere enmienda.

## Definición de hecho

Documentación: sin código. Los tests de D4 ya existen en `develop`.
