# Plan del paso 2 — datos de muestra históricos, perfil `demo-historico` (encargo del 2026-11-15)

**Estado:** APROBADO por Manuel el 2026-11-16 (sesión de trabajo; respuesta a las cinco decisiones del plan), con las condiciones de abajo. **Pendiente del visto bueno de Carlos** porque toca `core`: el PR no se fusiona sin su revisión. Redactado por Manuel con asistencia de Claude.
**Base:** `develop` del backend `c0ee6be`. Rama `claude/focused-dirac-8besqk`, PR hacia `develop`.
**Para qué:** ver cómo responde la predicción en cada caso y dar datos a los gráficos (paso 3). Se mantiene `baseline-0.3.0`: el problema de la demo se resuelve solo con datos; la enmienda del rango de entrenamiento se decide después de verlos.

## El hallazgo que obligó a tocar `core`

`AggregateRoot.raiseEvent` sellaba `occurredAt` con `Instant.now()` fijo. `/funds/monthly` y `/prediction/history` leen el `occurredAt` de los `FUNDS_CLEARED`: con un `Clock` controlado solo en `convocatoria` y `app`, todos los fondos caían en el mes actual y los cortes pasados salían con 0 % recaudado.

## Decisiones de Manuel (2026-11-16)

1. **Opción (1), endurecida:** una fuente de tiempo en el dominio de `core` (`EventTime`). Se descartan pasar un `Clock` por todos los comandos de `core` (cambio amplio en un módulo estable para una necesidad de demo) y no fechar `core` (no sirve).
2. **Composición:** la semilla histórica corre encima de `demo-seed` y reutiliza su organización y sus cuentas.
3. **Escenario:** las convocatorias de la tabla, más lotes en varios estados en dos convocatorias, una cerrada antes de tiempo e intenciones `PENDING`.
4. **Rama:** resuelto con la fusión del PR #180.
5. **Test intermitente de invitaciones:** `fix/` aparte; no cuenta como verde mientras exista.

## Garantías de la fuente de tiempo (condiciones de la aprobación)

Fechar hechos en el pasado es justo lo que un sistema de trazabilidad no debe permitir en producción.

| Garantía | Cómo | Test |
|---|---|---|
| Armado explícito | `EventTime.callAt` solo funciona si la fuente se armó; solo `DemoHistoricConfig` (perfil `demo-historico`) la arma. Sin armar: `SimulatedTimeNotEnabledException` | `EventTimeTest`, `DemoHistoricProfileAbsentTest` |
| Solo la semilla la llama | Regla de ArchUnit: nadie fuera de `com.traceability.app.bootstrap.historic` llama a `arm` ni a `callAt` | `SimulatedTimeArchitectureTest` (con su fixture que la regla debe detectar) |
| Sin fugas entre hilos | El instante simulado vive en el hilo y se restaura en `finally` | `EventTimeTest`: otro hilo y un pool de hilos reutilizados |
| Nunca en el futuro | `SimulatedTimeInFutureException`; `occurredAt <= recordedAt` por construcción | `EventTimeTest`, `DemoHistoricSeederIntegrationTest` |
| Fuera de `dev` no arranca | `DemoHistoricProfileNotAllowedException` al construir la configuración (como ADR-051 F1) | `SimulatedTimeArchitectureTest` |

`recordedAt` lo sigue poniendo el event store con el reloj real (`MongoEventStoreAdapter`, que no se toca). La cadena de hashes y el anclaje se calculan con el mismo código. La forma canónica heredada de ADR-039 Enmienda 1 depende de `recordedAt`, así que no se ve afectada.

## Componentes

| Componente | Módulo | Qué hace |
|---|---|---|
| `EventTime` y sus dos excepciones | `core`, `domain.shared` | De dónde sale `occurredAt`: el reloj real, salvo dentro de un bloque fechado con la fuente armada. `AggregateRoot` cambia una línea |
| `DemoHistoricConfig` | `app`, `bootstrap.historic` | Guarda de arranque, armado (dura lo que el contexto) y el `Clock` del perfil |
| `EventTimeClock` | ídem | El `Clock` que reciben los servicios por `ObjectProvider<Clock>`: lee la misma fuente |
| `DemoHistoricSeeder` | ídem | El escenario, por los servicios de aplicación |
| `DemoHistoricSeedRunner` | ídem | Lo lanza una vez, después de la semilla de `demo-seed` |

**Sin inserciones directas.** La semilla usa los mismos casos de uso que la API: crear convocatoria, crear intención, confirmar o fallar el pago, aplicar fondos, cerrar, registrar y mover lotes. La regla de la semilla actual se cumple; lo único nuevo es de dónde sale la fecha.

**No se tocan:** `DemoScenarioSeeder`, su lanzador, el golden path, `MongoEventStoreAdapter`, `application.yml` ni el `pom.xml` (los tres últimos los modifica la rama `claude/festive-newton-687sd0`).

## Contra qué documento se señala el cambio — **[Falta: decisión de Manuel]**

ADR-013 es la taxonomía de identificadores; no define `occurredAt`. **No hay ningún ADR que cubra la semántica de `occurredAt` y `recordedAt`.** La única definición escrita está en `Promt-Contexto.md` («Contrato del Evento»: `occurredAt`, cuándo ocurrió el hecho; `recordedAt`, cuándo lo guardó el sistema; «Invariante de Inmutabilidad Temporal»: `occurredAt` nunca posterior a `recordedAt`). El documento maestro de `develop` no la recoge, y el javadoc de `DomainEvent`, `EventType`, `DomainEventPayload` y `AggregateRoot` cita ADR-013 por error. Hasta este cambio el invariante no se comprobaba en código: se cumplía porque las dos fechas salían del reloj real.

Queda por decidir si hace falta un ADR nuevo (el siguiente libre es ADR-059) o basta una sección en el documento maestro (Tarea D). Este PR no crea ninguno de los dos.

## Escenario

Fechas relativas al momento de sembrar. Todos los títulos empiezan por «[Demo]» y la descripción dice que son datos sintéticos.

| Convocatoria | Moneda y política | Duración | Transcurrido | `/prediction` |
|---|---|---|---|---|
| [Demo] Antes del rango | COP, FLEXIBLE | 120 d | 7 % | `OUTSIDE_TRAINED_RANGE` |
| [Demo] Inicio del rango | COP, FLEXIBLE | 200 d | 17 % | cifra |
| [Demo] Un cuarto | COP, FLEXIBLE | 200 d | 25 % | cifra |
| [Demo] Cuarenta por ciento | COP, FLEXIBLE | 200 d | 40 % | cifra; cortes 0,15 y 0,25 con cifra |
| [Demo] Cerca del límite | COP, FLEXIBLE | 300 d | 47 % | cifra |
| [Demo] Después del rango | COP, FLEXIBLE | 200 d | 65 % | `OUTSIDE_TRAINED_RANGE`; los tres cortes con cifra |
| [Demo] Meta alcanzada | COP, FLEXIBLE | 120 d | 30 % | `TARGET_ALREADY_REACHED` |
| [Demo] Política estricta | COP, STRICT | 120 d | 30 % | `STRICT_POLICY_EXCLUDED` |
| [Demo] En dólares | USD, FLEXIBLE | 120 d | 30 % | `UNSUPPORTED_CURRENCY` |
| [Demo] Cerrada antes de tiempo | COP, FLEXIBLE | 100 d | 20 %, cerrada al 5 % | `CAMPAIGN_ENDED`; los tres cortes `CAMPAIGN_ENDED` |

- Donaciones confirmadas en fechas distintas a lo largo de unos cinco meses, pagos fallidos repartidos, una donación rechazada por exceder la meta en la STRICT (`FUNDING_REJECTED`) y tres intenciones `PENDING` sin resolver.
- Lotes en especie en «Cuarenta por ciento» y «Después del rango»: registrado, despachado, recibido y entregado en cada una, y uno dividido en la segunda. El hijo de la división lo crea una saga en otro hilo: lleva fecha real.

## Tests (rojos primero)

1. `EventTimeTest` (`core`): sin armar, armado, excepción dentro del bloque, futuro, desarmado, doble armado, anidado, otro hilo y pool de hilos reutilizados.
2. `mvn test -pl core` completo.
3. `DemoHistoricSeederIntegrationTest` (`app`, Testcontainers): la respuesta de cada convocatoria; los cortes del historial; meses de fondos; `occurredAt <= recordedAt` y `recordedAt` real en todos los eventos; la cadena de hashes de todos los flujos; los lotes; las intenciones; el escenario de `demo-seed` intacto; el reloj real después de sembrar; y que repetir la semilla falla sin crear nada.
4. `SimulatedTimeArchitectureTest` y `DemoHistoricProfileAbsentTest`: las aserciones negativas.
5. `scripts/ci-local.sh` con la salida literal.

## Documentación de este PR

- `runbook-demo-local.md`: sección del perfil, cuánto dura cada caso y cómo resembrar.
- Nota fechada en `ADR-044-enmienda-1-prediccion-en-backend.md`.
- La tabla de respuestas literales en `Documentos/evidencia-fase6/`.
- Sin tocar `estado-fase6.md` ni el documento maestro (decisión del 2026-11-15; van en la Tarea D).

## Fuera de este paso

Los gráficos (paso 3), las enmiendas de fotos (paso 4), la documentación consolidada (paso 5), el código de la Enmienda 6 de ADR-037 y cualquier cambio del modelo entrenado.
