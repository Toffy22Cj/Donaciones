# ADR-031 — Taxonomía de `ActorRef`

## Status
Reconstrucción histórica (Aprobada en Fase 5)

## Contexto
Tras decidir la persistencia y ubicación de `actorRef` (ADR-030), se requería definir el modelo de tipos que representa a los diferentes actores que interactúan con el sistema de trazabilidad (procesos del sistema, llamadas de integración externa, usuarios humanos).

## Problema
1. ¿Cómo debe modelarse el tipo `ActorRef`: un `enum` cerrado, un `String` plano o una jerarquía de tipos fuertemente tipada?
2. ¿Qué variantes son válidas en la Fase 5 inicial?
3. ¿Cómo se evita la proliferación de actores provisionales o ficticios antes de que el módulo `identity` esté integrado?
4. ¿Qué ocurre con la representación de actores humanos (`HumanAccount` / `HumanActor`)?

## Decisión Histórica Reconstruida

### 1. Jerarquía Sellada y Extensible
`ActorRef` se define como una interfaz sellada (`sealed interface`) en el paquete `com.traceability.core.domain.event`:
- Se rechazó el uso de un `String` plano (propicio a inconsistencias y falta de validación).
- Se rechazó el uso de un `enum` cerrado (impediría transportar metadatos específicos por tipo de actor).
- La interfaz sellada permite exhaustividad en tiempo de compilación y control estricto sobre las variantes autorizadas.

### 2. Variantes Iniciales de Fase 5
Las variantes autorizadas en el inicio de la Fase 5 fueron:
1. **`SystemActor(String policyName)`**: Representa acciones autónomas del sistema, ejecutadas típicamente por políticas de saga (e.g. `AssetRegisteredSagaPolicy`) o schedulers internos.
2. **`ExternalActor(String sourceSystem, String externalEventId)`**: Representa invocaciones originadas por sistemas externos (e.g. pasarelas de pago, webhooks). Reutiliza el concepto de `externalEventId` de ADR-013 sin validar autenticidad criptográfica externa.
El antiguo concepto de campo `origin` quedó completamente absorbido por esta taxonomía.

### 3. Prohibición de Actores Provisionales y Aplazamiento de Cuentas Humanas
- Se estableció una prohibición expresa de representar atribuciones mediante `null`, cadenas de texto libre o actores provisionales de prueba.
- La variante de actor humano (`HumanAccount`) quedó **deliberadamente aplazada** hasta que se definiera la arquitectura de autorización e identidad (lo que posteriormente derivó en `HumanActor(String accountId)` en ADR-035).
- Como consecuencia directa, los comandos que requerían autor humano (como la Tarea 5.4, Camino B de donación en especie) mantuvieron su capa de Application Service bloqueada hasta la resolución formal de dicha variante.

### 4. Persistencia Polimórfica en MongoDB
Para la serialización y deserialización BSON, se definieron conversores personalizados de Spring Data MongoDB (`ActorRefWriteConverter` y `ActorRefReadConverter`), utilizando el discriminador de tipo `_class`.

## Evidencia en Código y Repositorio
- `com.traceability.core.domain.event.ActorRef.java`: Declaración de la interfaz sellada permitiendo `SystemActor`, `ExternalActor` (y posteriormente `HumanActor`).
- `com.traceability.core.domain.event.SystemActor.java`.
- `com.traceability.core.domain.event.ExternalActor.java`.
- `com.traceability.core.infrastructure.persistence.mongo.converters.ActorRefWriteConverter.java`.
- `com.traceability.core.infrastructure.persistence.mongo.converters.ActorRefReadConverter.java`.
- Documentos de referencia: `estado-fase5.md` (§2), `plan-ejecucion-agentes-fase5.md` (Bloque 1, Regla #9 y Tarea 5.0), `Documentos/ADR-035-human-actor-authorization.md` (cita directa al ADR-031).

## Consecuencias
### Positivas
- Tipado fuerte y extensible que previene la entrada de actores no identificados o estructurados de forma arbitraria.
- Discriminación transparente de actores automáticos frente a actores externos.
- Integridad en la base de eventos almacenada.

### Negativas / Restricciones
- Bloqueó temporalmente la implementación completa de Application Services dependientes de operadores humanos hasta la introducción de ADR-035.
