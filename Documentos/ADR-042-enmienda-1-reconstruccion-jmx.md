# ADR-042 — Enmienda 1: reconstrucción operativa de las proyecciones por JMX

**Estado:** **PROPUESTA** (2026-10-07). Redactada bajo la segunda autorización de trabajo autónomo de Carlos, P4 ("solo documentos … enmienda de ADR-042 para la reconstrucción por JMX (B-PROJ PR 2), PROPUESTA"). **No autoriza código.** Es la precondición que fija `plan-b-proj.md` §3.3 y Q4: "mecanismo nuevo; enmienda de ADR-042 aprobada antes del merge del PR 2".
**Enmienda a:** ADR-042 (Aprobado, Fase 5). Añade un mecanismo; no cambia ninguna de sus decisiones 1–7.
**No toca:** el event store, payloads, versiones de esquema, canonicalización, hash, Merkle ni anclaje. Solo modelos de lectura reconstruibles (documento maestro, principio rector). **Nada de B4** (ADR-039 Enmienda 1).

---

## 1. Contexto

- ADR-042 deja dos vías de recuperación de la capa de lectura: el reintento automático (ventana de 4 h) y `resumeProjection`, que reprocesa **eventos en cuarentena** de una proyección que ya existe.
- B-PROJ PR 1 (#42) corrigió por qué las proyecciones no procesaban streams reales (génesis en 1, payloads v2). Lo escrito antes está en `quarantined_projections` o en reintento, y **sus proyecciones nunca existieron**: `resumeProjection` no puede liberarlas (`plan-b-proj.md` §3.3).
- `ProjectionRebuildService.rebuildAll()` existe pero **solo lo usan tests**, no tiene operación JMX, reconstruye un único manejador (`DonationProjectionHandler`), no vacía `donation_audit_facts`, `pending_allocations` ni `quarantined_projections`, aborta ante el primer evento que falla y, si falla, deja parada la fuente del change stream (no hay `finally`).

## 2. Decisión propuesta

1. **Operación JMX `rebuildProjections()`**, en un MBean de administración de proyecciones, con el mismo patrón que `resumeProjection` (ADR-042) y `BlockchainAdminOperationsService` (ADR-022). **Sin endpoint HTTP** (matriz §6: operaciones internas sin HTTP).
2. **Alcance:** todos los manejadores con estado, en su orden fijo (`@Order`): `DonationProjectionHandler`, `DonationAuditFactsHandler`, `PendingAllocationProjectionHandler`.
3. **Secuencia:**
   1. parar la fuente (`ProjectionEventSource.stop()`);
   2. tomar el *resume token* actual **antes** de leer;
   3. vaciar las colecciones de lectura de esos manejadores (las que hoy vacía `rebuildAll`: proyección de donaciones, historial e índice de activos; más `donation_audit_facts` y `pending_allocations`) **y** `quarantined_projections` y los documentos de reintento pendientes, que la reconstrucción deja superados;
   4. recorrer el event store por stream y `sequence` ascendente (el orden dentro del stream es lo que exige la integridad; entre streams no hay orden);
   5. guardar el *resume token* del paso 2 como checkpoint;
   6. **siempre** (`finally`) volver a arrancar la fuente: lo escrito durante la reconstrucción se proyecta después, y la idempotencia por `sequence` evita duplicados.
4. **Un evento que falla no aborta:** se registra (stream, `sequence`, manejador y clase del error, **sin payload** para no volcar datos) y la reconstrucción sigue. Al final devuelve un resumen: eventos leídos, proyectados, fallidos por manejador y duración.
5. **Una sola reconstrucción a la vez:** un candado en MongoDB (documento con `_id` fijo y caducidad). Una segunda llamada mientras hay otra en curso se rechaza con un mensaje, sin efectos.
6. **No interfiere con ADR-042:** mientras dura, la fuente está parada y `ProjectionRetryScheduler` no tiene documentos que reintentar (se vaciaron). `resumeProjection` sigue siendo la vía para una cuarentena puntual posterior.
7. **Procedimiento operativo** (`plan-b-proj.md` §3.3): parar el tráfico de escritura si es posible; ejecutar `rebuildProjections()`; comprobar que `quarantined_projections` queda vacío y que el seguimiento de una donación conocida devuelve importes.

## 3. Consecuencias

- **Positivas:** recuperación completa y repetible de la capa de lectura sin tocar la fuente de verdad; elimina el riesgo de dejar la fuente parada; cubre los tres manejadores con estado.
- **Negativas:** durante la reconstrucción las lecturas devuelven datos parciales (las colecciones se vacían al empezar). Con el volumen de la demo son segundos; con volumen real haría falta reconstruir en colecciones nuevas y cambiar el nombre al final (fuera de esta enmienda).
- **Riesgo aceptado:** un evento que falla en la reconstrucción queda sin proyectar hasta que se corrija la causa y se repita la operación; el resumen lo hace visible.

## 4. Tests exigidos al PR 2 (de `plan-b-proj.md` §3.3)
- reconstrucción desde un event store con streams v1 y v2, génesis en 1 y una cuarentena previa;
- un evento que falla no aborta la reconstrucción ni deja parada la fuente;
- eventos escritos durante la reconstrucción se proyectan después;
- una segunda reconstrucción concurrente se rechaza sin efectos.

## 5. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q-E1 | ¿El candado de una sola reconstrucción (§2.5) es necesario con una sola instancia? | Sí: es barato y evita dos llamadas JMX solapadas |
| Q-E2 | ¿Se vacían los documentos de reintento pendientes (§2.3), o se dejan para que el scheduler los descarte por idempotencia? | Vaciarlos: la reconstrucción los supera y así el scheduler no compite |
| Q-E3 | ¿Lecturas parciales durante la reconstrucción (§3) aceptables para la demo? | Sí; la reconstrucción en colecciones paralelas queda para producción |
