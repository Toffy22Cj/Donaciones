# Reglas de Trabajo — Equipo y Agentes de Código

**Proyecto:** Motor de Trazabilidad Verificable de Donaciones (`com.traceability`)
**Alcance:** este documento rige a partir de que el proyecto pasa de trabajarse en solitario a trabajarse en equipo (humanos + agentes de código). No reemplaza `documento-maestro-proyecto.md` ni `plan-ejecucion-agentes-fase2.md` — los complementa con las reglas de proceso que antes se aplicaban manualmente, una conversación a la vez.

**Por qué existe este documento:** durante la Fase 2, trabajando en solitario, hubo múltiples episodios donde un agente presentó como completado o verificado un trabajo que no lo estaba — un `walkthrough.md` describiendo tareas no ejecutadas, un adaptador que fabricaba citas de IA en vez de exigirlas del modelo, un documento de diseño que retrocedía silenciosamente decisiones ya aprobadas, un archivo `SUPER_PROMPT_FASE3.md` creado sin autorización asumiendo el siguiente paso "lógico". Cada uno se detectó porque hubo una sola persona revisando con extremo cuidado, línea por línea, cada entrega. Con varias personas y varios agentes trabajando en paralelo, esa revisión manual exhaustiva deja de ser viable — así que las reglas que antes vivían en la cabeza de un revisor tienen que vivir ahora en un proceso explícito.

---

## 1. Principio rector

> Ningún agente ni ninguna persona tiene autoridad para auto-aprobarse. Toda decisión arquitectónicamente significativa requiere aprobación humana explícita antes de convertirse en código. Todo estado reportado como "completado" o "verificado" requiere evidencia de ejecución real, no una descripción de que debería funcionar.

Esto no es burocracia decorativa — es la misma regla que ya demostró su valor una y otra vez: cada vez que se aceptó una afirmación sin exigir la evidencia detrás, apareció un problema real (una colisión de nonce posible, una validación de seguridad que nunca se ejecutaba, una máquina de estados que retrocedía sin que nadie lo notara).

---

## 2. Reglas para los agentes de código

### 2.1 No inventes, no asumas, no te adelantes

- Nunca implementes algo que no esté explícitamente en el contrato de la tarea actual. Si crees que falta algo, repórtalo — no lo agregues por iniciativa propia.
- Nunca crees un documento, archivo o "siguiente fase" que no haya sido explícitamente discutido y aprobado en la conversación con el equipo. Si el siguiente paso te parece obvio, pregúntalo — no lo ejecutes.
- Si detectas una contradicción, ambigüedad, o un caso no cubierto por los ADRs vigentes: **detente**. No lo resuelvas por tu cuenta ni "interpretes la intención". Documenta el conflicto exacto (qué ADR, qué línea del contrato, qué caso no cubre) y espera instrucción.
- Cada clase pública debe poder señalarse contra un ADR específico. Si no puedes señalar cuál, detente y pregunta antes de escribirla.

### 2.2 No fabriques comportamiento para que un contrato "parezca" cumplido

Este es el hallazgo más grave que se repitió durante la Fase 2: código que superficialmente cumple una interfaz o un nombre de método, pero cuyo comportamiento real hace lo contrario de lo que el contrato exige (por ejemplo: un método que dice "extraer las citas que el modelo declaró" pero en realidad las construye él mismo con una heurística de texto; un `resolveStuckBatch` que en teoría es "exclusivamente manual" pero aparece invocado automáticamente dentro de un scheduler).

Antes de dar por cumplido un invariante de seguridad, concurrencia o integridad: relee el ADR que lo exige y compáralo literalmente contra el código, no contra tu recuerdo de la conversación donde se discutió.

### 2.3 Toda afirmación de "tests pasando" requiere el output real

- Nunca reportes un test como pasando basándote en que "el código está listo" o "debería funcionar" — solo en el resultado literal de la ejecución.
- Al extender un componente ya probado, corre el módulo completo (`mvn test -pl <módulo>`), nunca solo la clase nueva o modificada. Un cambio aparentemente aislado puede romper un mock o una aserción en un test que no tocaste directamente.
- Pega el resumen literal de Surefire (`Tests run: X, Failures: Y, Errors: Z`), no una tabla derivada, no una descripción cualitativa de "todo pasó".
- Si el entorno de ejecución falla (Docker, Testcontainers, versión de API), diagnostica la causa raíz comparando contra un módulo hermano que sí funciona — no reportes el bloqueo como "pendiente de que el usuario lo resuelva" sin antes intentarlo tú mismo.

### 2.4 Documentación de estado (`task.md`, `walkthrough.md`, y equivalentes)

- Se actualiza únicamente leyendo el resultado directo de la ejecución de la sesión actual. Cero inferencia de estado por memoria de sesiones previas.
- Antes de afirmar que un documento de estado "ya refleja" cierto trabajo, ábrelo y confírmalo — no asumas que una edición anterior se aplicó correctamente.
- Si generas una síntesis de un diseño ya aprobado en una conversación anterior (por ejemplo, para retomar un documento largo), compárala explícitamente contra el texto exacto ya congelado, no la reconstruyas desde tu propio entendimiento del tema. Una síntesis "razonable" puede perder silenciosamente una decisión crítica que costó varias rondas de corrección.

### 2.5 Verificación estructurada, no superficial

- Un test que verifica que un mock fue *construido* con el valor correcto no es equivalente a un test que verifica que ese valor *se usó realmente* en la operación final (ej. construir un `TransactionManager` con el nonce correcto no prueba que la transacción minada realmente llevó ese nonce). Cuando el riesgo lo justifique, escribe el test que verifica el resultado final, no solo la intención declarada en el camino.
- Las aserciones negativas (`verify(..., never())`) son obligatorias cuando existen varias ramas de comportamiento mutuamente excluyentes (ej. éxito vs. fallo determinista vs. fallo ambiguo). Verificar solo que la rama correcta se ejecutó, sin confirmar que las otras no se tocaron, deja huecos de cobertura reales.

### 2.6 Excepciones y estados: nombrados, específicos, con salida

- Toda condición de fallo tiene su propia excepción nombrada, nunca una genérica.
- Todo estado que un objeto puede alcanzar necesita una vía de salida explícita. Un estado sin transición de escape definida no es una máquina de estados completa — es una condición de bloqueo silencioso que solo aparece en producción.
- Diferencia siempre entre un fallo **determinista** (sabemos con certeza que nada externo ocurrió; se puede reintentar directo) y un fallo **ambiguo** (no sabemos si la operación externa se completó; requiere verificación antes de reintentar). Tratarlos igual es la fuente más común de duplicación de efectos en sistemas que hablan con infraestructura externa.

---

## 3. Reglas para el equipo humano

### 3.1 Estrategia de ramas

| Rama | Nomenclatura | Propósito |
|---|---|---|
| `main` | — | Producción/estable. Solo recibe merge desde `develop`. Nunca commits directos. |
| `develop` | — | Integración. Debe compilar y pasar `mvn test` en los cuatro módulos en todo momento. |
| feature | `feat/<módulo>-<tarea-breve>` | Ej. `feat/api-donation-controller`, `feat/config-web3-credentials`. |
| fix | `fix/<módulo>-<error-breve>` | Correcciones puntuales, con test de regresión que reproduzca el bug antes de corregirlo. |
| chore | `chore/<tarea-breve>` | Mantenimiento, configuración, dependencias, documentación. |

**Ninguna rama `feat/` se abre para una tarea que no haya pasado por Modo de Arquitectura + ADR** cuando la decisión sea arquitectónicamente significativa. La rama es el vehículo de trabajo ya aprobado, no el lugar donde explorar sin supervisión — esto es lo que evita que, a escala de equipo, se repita el patrón de "asumí que el siguiente paso lógico era X" que ya ocurrió trabajando en solitario.

### 3.2 Protección de rama

- PR hacia `develop`: mínimo una aprobación humana antes del merge.
- CI obligatorio corriendo `mvn test` en los cuatro módulos (`contracts`, `core`, `crypto`, `ai`) contra Testcontainers real — no se permite mergear con tests deshabilitados o con `-DskipTests`.
- PR hacia `main`: solo desde `develop`, nunca desde una rama feature directamente.

### 3.3 Checklist de revisión de PR (humano revisando a otro humano, o a un agente)

Antes de aprobar cualquier PR, confirmar:

1. ¿El código está señalado contra un ADR específico? Si introduce una decisión nueva, ¿existe el ADR correspondiente, o se está colando una decisión arquitectónica sin pasar por el proceso?
2. ¿El PR incluye el output real de los tests, no solo la afirmación de que pasan?
3. Si el PR extiende un componente existente, ¿se corrió el módulo completo, no solo la clase nueva?
4. ¿Hay algún estado nuevo (enum, máquina de estados) sin una vía de salida explícita?
5. ¿Hay alguna condición de fallo tratada de forma genérica en vez de con una excepción nombrada?
6. Si el PR toca infraestructura sensible (persistencia con concurrencia, integraciones externas, claves/secretos): ¿hay un test que ejercite la condición de carrera o el escenario de fallo real, no solo el camino feliz?
7. ¿La documentación de estado (`task.md`, `walkthrough.md`, `documento-maestro-proyecto.md`) quedó actualizada para reflejar exactamente lo que este PR agrega — ni más ni menos?

### 3.4 Cuándo se requiere un ADR nuevo

Antes de fusionar cualquier decisión que:
- introduzca una dependencia o tecnología nueva,
- cambie el límite de consistencia de un Aggregate,
- cambie el contrato de un puerto ya usado por otro módulo,
- introduzca un mecanismo de concurrencia, reintento o recuperación de fallos nuevo,

el equipo debe tener un ADR aprobado (formato: Context / Decision / Alternatives / Consequences / Status) antes de que el código correspondiente se mergee — no después, como documentación retroactiva.

---

## 4. Regla común a ambos: la pregunta que siempre hay que poder responder

Antes de marcar cualquier trabajo como terminado, agente o persona, debe poder responder con evidencia concreta —no con una afirmación— a esta pregunta:

> **¿Cómo sé que esto funciona, más allá de que el código compila y el nombre del método suena correcto?**

Si la respuesta es "porque debería", el trabajo no está terminado.
