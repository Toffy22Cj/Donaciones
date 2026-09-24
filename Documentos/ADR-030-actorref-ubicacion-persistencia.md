# ADR-030 — `actorRef`: Ubicación y Persistencia

## Status
Reconstrucción histórica (Aprobada en Fase 5)

## Contexto
En las Fases 1 a 4, los eventos de dominio en `core` registraban transiciones de estado y datos operativos, pero no capturaban de manera uniforme la atribución del sujeto o sistema que originó el comando. En Fase 5 (Tarea 5.0), se estableció la necesidad de registrar un `actorRef` en cada evento para propósitos de auditoría.

## Problema
1. ¿Cuál es el propósito exacto de `actorRef`: garantía criptográfica de autoría o registro de auditoría administrativa?
2. ¿Debe `actorRef` participar en el cálculo del hash del evento (`eventHash`), en `EventCanonicalMapper` (JCS) y en la cadena de bloques / árbol de Merkle?
3. ¿Debe formar parte de `DomainEventPayload` o del envoltorio de persistencia?
4. ¿Cómo se garantiza la consistencia atómica entre la persistencia del evento y su atribución?

## Decisión Histórica Reconstruida

### 1. Finalidad: Auditoría Administrativa
`actorRef` tiene como finalidad exclusiva la **auditoría administrativa y trazabilidad operativa**. Se rechazó atribuirle una garantía criptográfica de no repudio o firma digital, ya que la autenticación formal del usuario final y la firma criptográfica se encontraban fuera del alcance de la infraestructura de `core`.

### 2. Exclusión de la Cadena de Hash Criptográfico
- `actorRef` queda **estrictamente fuera** de `EventCanonicalMapper`, de la canonicalización JCS, de `eventHash`, de `previousHash` y de los árboles de Merkle de anclaje.
- Ningún test de hash criptográfico debe incluir `actorRef`.
- `DomainEventPayload` no contiene `actorRef`. El replay de un aggregate en Event Sourcing no requiere ni procesa `actorRef` para reconstituir su estado funcional.

### 3. Persistencia Atómica en `TraceabilityEventDocument`
- `actorRef` se persiste como un campo del envoltorio de infraestructura en MongoDB: `TraceabilityEventDocument.actorRef`.
- Se inserta en el mismo documento de MongoDB y en la misma operación atómica en la colección `event_store`.
- Esto elimina cualquier ventana de inconsistencia en la que un evento exista sin su correspondiente atribución.
- Es **inmutable por contrato de persistencia de base de datos**, explícitamente no protegido criptográficamente por la firma del hash.

### 4. Inexistencia de Corregibilidad Administrativa
No se admite la modificación posterior de `actorRef`. Se descarta la existencia de comandos de rectificación o backfill de atribución, garantizando la inmutabilidad del registro de auditoría.

## Evidencia en Código y Repositorio
- `com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument.java`: Campo `private ActorRef actorRef;` persistido en MongoDB.
- `com.traceability.core.application.event.EventCanonicalMapper.java`: Ausencia deliberada de `actorRef` en `toCanonicalMap(...)` (preserva JCS puro).
- `core/src/test/java/com/traceability/core/application/event/EventCanonicalMapperTest.java`: Pruebas de hash sin contaminación de metadatos de actor.
- Documentos de referencia: `estado-fase5.md` (§2), `plan-ejecucion-agentes-fase5.md` (Bloque 1, Tarea 5.0), `Documentos/blockchain-resumen.md` (reutilización del mismo patrón para `merkleBatchId`).

## Consecuencias
### Positivas
- Se garantiza la trazabilidad administrativa de cada evento registrado en el sistema.
- Cero impacto en la cadena de hash y en el determinismo criptográfico preexistente.
- El replay de eventos en los agregados de dominio permanece completamente puro y desacoplado del contexto de ejecución de comandos.

### Negativas / Restricciones
- La atribución no posee certificación criptográfica autónoma; su veracidad depende de la seguridad del perímetro de la base de datos y de las políticas de la capa de aplicación.
