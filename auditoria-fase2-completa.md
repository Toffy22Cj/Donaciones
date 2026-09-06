# Auditoría Exhaustiva de Fase 2 — Verificación Contra Código Real

**Contexto para quien lea esto antes de pegarlo al agente:** esta auditoría nace de un patrón repetido. En tres ocasiones dentro de esta misma sesión de diseño (Tarea 10.1, Tarea 10.2, y el hallazgo recién descubierto sobre `SagaPolicy`), una tarea marcada `✅ COMPLETADA` en `documento-maestro-proyecto.md` resultó tener una discrepancia real entre lo documentado y lo implementado — descubierta solo porque alguien necesitó ese componente para construir algo encima, no porque una revisión sistemática la hubiera encontrado antes. Esta auditoría existe para invertir ese orden: encontrar las discrepancias buscándolas deliberadamente, no esperando a tropezar con ellas.

---

## Prompt para el agente (pegar completo, en una sesión nueva o encadenada)

```
Eres el mismo Ingeniero de Software Senior que ha venido implementando este
proyecto. Tu tarea ahora NO es escribir código. Es auditar, tarea por tarea,
cada una de las 14 tareas de Fase 2 marcadas como "✅ COMPLETADA" en
documento-maestro-proyecto.md y plan-ejecucion-agentes-fase2.md, verificando
si la implementación real coincide con lo que esos documentos afirman.

REGLA DE ORO DE ESTA AUDITORÍA:
No confíes en la palabra "COMPLETADA". Ya se demostró tres veces en esta sesión
que ese estado puede estar mal — no por mala fe, sino porque "compila y los
tests pasan" no es lo mismo que "cubre todos los casos que el propio contrato
de la tarea exige". Tu trabajo es encontrar la cuarta vez, la quinta, y
cualquier otra que exista, no confirmar que todo está bien.

METODOLOGÍA OBLIGATORIA PARA CADA TAREA (no te saltes ningún paso):
1. Lee el contrato original de la tarea en plan-ejecucion-agentes-fase2.md —
   qué prometía exactamente (comandos, eventos, invariantes, exclusiones
   explícitas bajo "QUÉ NO HACER").
2. Localiza el código real que supuestamente implementa ese contrato.
3. Construye una tabla exhaustiva: cada elemento prometido (comando, rama de
   evento, invariante, exclusión) vs. su estado real en el código, citando el
   fragmento exacto — nunca un resumen de lo que "parece que hace".
4. Para cualquier componente con múltiples ramas de decisión (switch,
   if/else if, cadenas de instanceof), verifica explícitamente que el
   inventario de casos posibles esté completo — no asumas que 4 ramas
   visibles son todas las que existen. Cuenta primero cuántos casos reales
   hay (payloads, eventos, estados), luego verifica cobertura contra ese
   número.
5. Si encuentras algo que no coincide con el contrato, NO LO CORRIJAS.
   Repórtalo con la misma tabla evento→código→discrepancia que usamos para
   Tarea 10.1, 10.2 y el hallazgo de SagaPolicy. La corrección es una decisión
   posterior, humana, no tuya.
6. Si una tarea depende de un componente ya auditado en una ronda anterior
   (por ejemplo, Tarea 10 depende de la Tarea 9), no vuelvas a auditar ese
   componente — referencia el hallazgo anterior.

QUÉ NO HACER:
- No digas "parece correcto" o "no encontré problemas" sin mostrar la tabla
  completa de verificación que te llevó a esa conclusión.
- No corrijas nada todavía, ni siquiera algo que te parezca trivial.
- No asumas que una tarea posterior en la secuencia "seguramente ya cubrió"
  algo que la tarea anterior dejó incompleto — verifícalo tú mismo.
- No declares una tarea "limpia" solo porque compiló y sus tests originales
  pasan — sus tests originales pudieron no cubrir el caso que estás buscando,
  exactamente como pasó con Tarea 10 antes de 10.1/10.2.

ORDEN DE AUDITORÍA Y QUÉ VERIFICAR EN CADA UNA:

### Tarea 0 — Scaffolding Maven
Verifica que las reglas de ArchUnit realmente estén activas y se ejecuten en
el build (no solo que existan como clase de test) — confirma con
`mvn test -Dtest=*ArchTest*` o el nombre real de la clase, y pega el output.

### Tarea 1 — AggregateRoot, EventStream, contratos base
Verifica `rehydrate()`: confirma con una cita exacta que un evento aplicado
durante replay NUNCA se agrega a `uncommittedEvents` (esto es crítico — si
tuviera un bug, cada replay re-emitiría eventos ya persistidos). Verifica
`validateNextSequence` contra los dos casos (salto y repetición) con cita del
método, no solo de su test.

### Tarea 2 — contracts: HashPort, AuditFactsPort, DTOs
Verifica que `contracts` compila de forma aislada (sin ninguna dependencia
más que el JDK) ejecutando el build de ese módulo solo. Confirma que
`AuditFactsDTO` no ganó, en tareas posteriores (11, 12), ningún campo que
dependa de MongoDB o de estructura interna de `core`.

### Tarea 3 — Aggregate PhysicalAsset
Ya sabemos, por Tarea 10.2, que el AGGREGATE en sí (`Fund.apply()`) se auditó
limpio. PhysicalAsset.apply() NO se ha auditado con el mismo rigor todavía.
Construye la tabla completa de los 8 eventos posibles contra el `switch`/
`apply()` de PhysicalAsset (no contra la proyección, que ya sabemos que tenía
huecos — esto es el Aggregate, la fuente de verdad). Verifica en particular:
- La "resurrección" desde DEPLETED en CompensateAssetSplit — confirma que el
  Map<childAssetId, statusBeforeSplit> mencionado en el contrato de la Tarea 3
  realmente existe y se puebla correctamente durante el replay, no solo en
  escritura en vivo.
- Las 6 excepciones de dominio listadas en el contrato — confirma que las 6
  existen como clases nombradas y que cada invariante que dice lanzarlas
  realmente lo hace (no una genérica capturada tarde).

### Tarea 4 — Payloads de PhysicalAsset
Verifica que ningún payload ganó un campo no listado en el contrato original
en ninguna tarea posterior (búsqueda rápida, bajo riesgo).

### Tarea 5 — Aggregate Fund
YA AUDITADO en esta sesión — Fund.apply() confirmado limpio, cubre los 6
eventos exhaustivamente. No repitas este trabajo. Sí verifica algo que no se
revisó todavía: los COMANDOS (no el apply de eventos) — `registerFund`,
`clearFundsGenesis`, `requestAllocation`, `confirmAllocation`,
`reverseAllocation`, `refund` — confirma que cada uno valida sus
precondiciones exactamente como las describe la sección 6.2 del maestro
(ecuación de `availableAmount`, invariante de refund).

### Tarea 6 — Payloads de Fund
Ya sabemos, por el hallazgo de `currency`/`campaignRef`, que hay una
discrepancia real aquí entre lo documentado (sección 6.2) y lo implementado.
No la vuelvas a auditar — ya está confirmada y registrada como deuda técnica
pendiente de decisión humana. Sí verifica si hay OTRO campo documentado en
sección 6.2 (`donorRef`, por ejemplo) que tampoco esté en los payloads reales.

### Tarea 7 — OutboxSagaCoordinator + SagaPolicy — PRIORIDAD MÁXIMA
Ya se encontró un hallazgo grave: no se encontró ninguna implementación
concreta de `SagaPolicy` en `main`, solo un mock en tests. Antes de asumir que
esto es definitivo, verifica dos cosas adicionales que quedaron pendientes:
1. Búsqueda textual exacta de `implements SagaPolicy` en TODO el repositorio
   (no solo en el paquete `saga`) — puede existir con un nombre de clase que
   no anticipamos.
2. Confirma si `OutboxSagaCoordinator.processPendingMessages()` está
   realmente invocado por algún `@Scheduled` o mecanismo de disparo activo en
   `app` o `core` — o si, además de no tener políticas, el propio coordinador
   nunca se ejecuta en producción. Cita el bean/configuración exacta si
   existe, o confirma su ausencia.
3. Si se confirma que no hay políticas concretas: enumera explícitamente qué
   comandos deberían dispararse y nunca se disparan como consecuencia directa
   (`ALLOCATION_CONFIRMED` tras registro exitoso del asset, `RegisterPhysicalAsset`
   del hijo tras un split, `ALLOCATION_REVERSED`/`ASSET_SPLIT_COMPENSATED` tras
   fallo permanente) — con cita de dónde debería estar ese disparo y no está.

### Tarea 8 — crypto: JcsHashAdapter + MerkleTree
Verifica el caso borde explícitamente mencionado en el diseño original:
número impar de hojas en el Merkle Tree (debe duplicar la última). Confirma
con cita de código y, si existe, del test que lo cubre. Verifica que
`eventHash` nunca participa en el material que se hashea para calcularlo a sí
mismo (regla explícita del pipeline).

### Tarea 9 — EventStorePort + Outbox (MongoDB)
YA AUDITADO en esta sesión — confirmado limpio (índice único, manejo de
`DuplicateKeyException` → `ConcurrencyConflictException`). No repitas. Sí
verifica algo que no se confirmó: si tras una `ConcurrencyConflictException`,
el Application Handler realmente recarga y reevalúa el comando completo (paso
7 del protocolo de concurrencia optimista de la sección 8.1 del maestro), o
si en algún caso existente simplemente reintenta con el mismo evento ya
construido — que sería exactamente el error de "incrementar la versión a
ciegas" que se discutió y rechazó explícitamente en el diseño de Fase 1.

### Tarea 10 (y 10.1/10.2) — Proyecciones CQRS
YA AUDITADO exhaustivamente en esta sesión. No repitas.

### Tarea 11 — DonationAuditFacts + framework genérico de proyección
Verifica que el alcance declarado en sección 8.4 del maestro
(`DISPATCHED→RECEIVED`, `DISPATCHED→DELIVERED`, `RECEIVED→DELIVERED`) es
exactamente lo que el código cubre — ni más ni menos. Si falta o sobra algo
respecto a esa declaración, repórtalo. Verifica también si este handler
sufre del mismo patrón de rama incompleta que encontramos en
`DonationProjectionHandler` — cuenta los tipos de payload de PhysicalAsset
que SÍ debería ignorar (según el alcance declarado) contra los que realmente
ignora, para confirmar que la exclusión es intencional y no accidental.

### Tarea 12 — ai: NarrativeGenerator
Verifica el single-flight (`ConcurrentHashMap<CacheKey, CompletableFuture>`)
— confirma con cita que no hay trabajo bloqueante dentro de
`computeIfAbsent`, tal como exige ADR-018 explícitamente. Verifica que el
`GroundingValidator` realmente invalida la respuesta completa ante un solo
`CitedFact` no verificable (no solo descarta ese hecho individual).

### Tarea 13 — crypto.infrastructure.web3j: BlockchainAnchorAdapter
Verifica la guardia de prioridad obligatoria: confirma con cita que
`findSubmittingWithoutTxHashAndNonce()` se ejecuta ANTES de cualquier claim
de un batch `PENDING`, en cada ciclo del scheduler — no solo en el arranque.
Verifica que `resolveStuckBatch` efectivamente no tiene ninguna invocación
automática en todo el código (grep exhaustivo de todas sus referencias).

### Tarea 14 — app: Ensamblaje de Bootstrap
Ya se registraron dos hallazgos de esta tarea como deuda técnica (HashPort
sin @Component hasta esta tarea, workaround de Docker). Verifica si hay un
tercer bean de infraestructura de `core`, `crypto` o `ai` que, igual que
`HashPort`, nunca haya sido ejercitado por ningún ensamblaje real hasta
ahora — es decir, repite el mismo tipo de auditoría que encontró el problema
de `HashPort`, pero para el resto de beans `@Component`/`@Bean` del sistema.

FORMATO DE ENTREGA:
Para cada tarea, una sección con: tabla de verificación completa, veredicto
(LIMPIO / DISCREPANCIA ENCONTRADA), y si hay discrepancia, su severidad
estimada (por impacto: ¿afecta integridad de datos, disponibilidad, o es
cosmético?). Al final, un resumen consolidado de todas las discrepancias
encontradas, ordenadas por severidad, para que un humano decida cuáles
ameritan una tarea de corrección formal (estilo Tarea 10.1/10.2/7.1) y cuáles
se registran como deuda técnica consciente.

NO PROPONGAS CORRECCIONES DE CÓDIGO EN ESTE DOCUMENTO. Esto es un reporte de
auditoría, no un plan de implementación. El plan de corrección se redacta
después, en una conversación separada, una vez que el humano haya revisado
los hallazgos.
```

---

## Nota para la reunión de mañana

Este documento produce un inventario de riesgo, no una lista de trabajo lista para ejecutar. Dependiendo de lo que arroje — especialmente la confirmación completa del hallazgo de `SagaPolicy` — la decisión de si esto amerita reabrir formalmente el cierre de Fase 2 (cambiar el estado de "Fase 2: cerrada" en el documento maestro) es una decisión de equipo, no algo que el agente o esta auditoría deban decidir por su cuenta.
