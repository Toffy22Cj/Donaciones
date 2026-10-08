# Plan de corrección — Fase 5 (auditoría) + IA (ADR-036) + colisión de numeración

**Origen:** dos auditorías independientes de Fase 5 (coincidentes en sus hallazgos principales) + una tercera fuente ("documentación actualizada" de Fase 5, que introdujo hallazgos nuevos pero también una contradicción interna propia) + el review de 12 puntos de IA hecho durante la planeación de Fase 6 (ADR-036 de Fase 6, cerrado parcialmente).
**Naturaleza de este documento:** plan de trabajo para asignar y ejecutar. Cada ítem tiene hallazgo, evidencia, acción concreta, criterio de éxito verificable y responsable sugerido — nada se marca como resuelto sin ese criterio cumplido con evidencia real (mismo estándar aplicado durante todo Fase 6).
**Advertencia de procedencia:** la "documentación actualizada" que motivó la revisión de este plan (`estado-fase5.md`/`plan-ejecucion-agentes-fase5.md`, versión posterior) no debe tratarse como fuente resuelta — contiene al menos una contradicción interna propia (ver A6) y repite cifras de test sin evidencia fresca. Se trata como una tercera fuente a verificar, con el mismo rigor que las dos auditorías, no como la respuesta final.

---

## 🔴 Hallazgo crítico, resolver antes que cualquier otro ítem: colisión de numeración de ADR entre Fase 5 y Fase 6

**Hallazgo:** los ADR-033 a ADR-036 de **Fase 5** (saga de registro de activos, visibilidad de pending allocation, HumanActor, reversión administrativa) usan exactamente los mismos números que los ADR-033 a ADR-036 de **Fase 6** generados en esta sesión (Convocatoria, Identidad, Blockchain, IA respectivamente) — dos conjuntos de decisiones completamente distintas con la misma numeración. El propio documento de Fase 5 lo admite: *"Aviso de Colisión Documental: Existe una colisión de numeración para ADR-033 a ADR-036 introducida posteriormente por el trabajo de Fase 6."*

**Por qué es urgente y no solo una deuda documental:** cualquier referencia futura a "ADR-035" es ambigua sin contexto — podría significar HumanActor (Fase 5) o el diseño de MerkleBatch (Fase 6, Blockchain). Ya generamos cinco ADR de Fase 6 en esta sesión con esta numeración en conflicto.

**Acción:**
1. Confirmar el número real más alto del catálogo de ADR existente (Fase 5 llega hasta ADR-036 según la fuente más reciente — verificar que no haya nada más entre ADR-036 y el inicio de Fase 6 antes de asumir el siguiente número disponible).
2. Renumerar los cinco ADR de Fase 6 generados en esta sesión a partir del siguiente número libre confirmado (candidato, sujeto a confirmación: ADR-037 Convocatoria, ADR-038 Identidad, ADR-039 Blockchain, ADR-040 IA, ADR-041 APIs/Frontend).
3. Actualizar cualquier referencia cruzada entre esos cinco documentos que cite el número antiguo.

**Criterio de éxito:** un único catálogo de ADR sin números duplicados, confirmado contra el repositorio real.

**Responsable sugerido:** quien mantenga el catálogo de ADR — es una corrección administrativa urgente, no requiere debate de arquitectura.

---

## Resumen ejecutivo

| Bloque | Ítems | Bloquea Fase 6 |
|---|---|---|
| Colisión de ADR | 1 hallazgo crítico | Sí — ambigüedad activa en cualquier referencia nueva a ADR-033/034/035/036 |
| A — Fase 5 / Core / Identity | 6 hallazgos (5 originales + A6 nuevo) | Sí — 2 de ellos bloquean directamente trabajo de Blockchain y Convocatoria |
| B — IA / ADR-036 (Fase 6) | 3 decisiones estructurales + 1 contradicción + 4 verificaciones técnicas | No bloquea otras capas, sí bloquea completar el diseño de `ConvocatoriaAuditFacts` |

---

## Bloque A — Fase 5 (confirmado por dos auditorías independientes)

### A1. Contexto de test roto — `MongoUnanchoredEventAdapterTest` (PRIORIDAD MÁXIMA, bloquea Blockchain hoy mismo)

**Hallazgo:** el test de Blockchain (`crypto`/`core`) no puede arrancar su contexto de Spring porque escanea de más y arrastra `FundCommandService`, que depende de `IdentityPrincipalPort` — un bean que el test no provee.

**Evidencia:** `No qualifying bean of type 'com.traceability.contracts.authorization.IdentityPrincipalPort' available` — confirmado en dos ejecuciones distintas, una de ellas dentro del reactor completo (`core FAILURE`, `identity`/`app` `SKIPPED`).

**Causa raíz real:** no es que falte implementar `IdentityPrincipalPort` — es que el test usa un `@SpringBootApplication(scanBasePackages="com.traceability.core")` demasiado amplio, el mismo patrón de sobre-escaneo ya detectado dos veces hoy en Blockchain (con `HashPort` y con la clave de OpenAI).

**Acción:**
1. Acotar el contexto del test a lo que realmente ejercita — usar `@ContextConfiguration(classes = {MongoUnanchoredEventAdapter.class, ...})` o un slice equivalente, en vez de `@SpringBootApplication` escaneando todo `core`.
2. **No** proveer un mock de `IdentityPrincipalPort` para "hacer arrancar" el contexto amplio — eso oculta el síntoma sin arreglar la causa (el test seguiría cargando cosas que no necesita).
3. Verificar que el mismo patrón no está repetido en otros tests de `crypto`/`core` que hoy "funcionan por casualidad" con el contexto amplio.

**Criterio de éxito:** `mvn test -pl core,crypto,app` completo en verde, con el mismo desglose por clase que ya usamos toda la sesión — sin necesidad de ningún mock de `IdentityPrincipalPort`.

**Responsable sugerido:** quien mantenga la infraestructura de tests de `core`/`crypto` — es un arreglo de configuración, no de lógica de negocio, estimable en horas, no días.

---

### A2. Autorización ausente en cuatro métodos (PRIORIDAD ALTA, antes de exponer HTTP) — VERIFICACIÓN PENDIENTE, no cerrar sin evidencia nueva

**Hallazgo original (ambas auditorías, con cita de código real):** `requestAllocation`, `confirmAllocation`, `reverseAllocation` (en `FundCommandService`) y `deliverAsset` (en `PhysicalAssetCommandService`) reciben un `actorRef`/`HumanActor` como parámetro pero nunca invocan `authorize`/`resolvePrincipal` sobre él — a diferencia de `registerFund`, `clearFundsGenesis`, `reverseAllocationAdministratively`, `registerPhysicalAsset` y `splitPhysicalAsset`, que sí lo hacen correctamente.

**⚠️ Discrepancia sin resolver introducida por la tercera fuente:** la "documentación actualizada" de Fase 5 solo reconoce `requestAllocation` y `reverseAllocation` como deuda pendiente de autorización — **no menciona** `confirmAllocation` ni `deliverAsset`. Dos lecturas posibles, ninguna confirmada: (a) esos dos sí fueron corregidos por NUEVA-5 y el documento no lo actualizó explícitamente en la lista de deudas, o (b) siguen sin autorización y es una omisión real del documento. **No asumir ninguna de las dos sin ver el código actual de ambos métodos.**

**Mitigante confirmado (todavía vigente):** ninguna auditoría encontró un controlador HTTP que invoque estos métodos hoy — el hueco no es explotable externamente *todavía*.

**Por qué es prioridad alta y no solo media:** ADR-037 (APIs/Frontend, ya diseñado en esta sesión) construirá los controladores que eventualmente invocarán estos comandos. Si esto no se corrige antes de que exista esa capa HTTP, el hueco se vuelve explotable el mismo día que se conecte la API.

**Acción:**
1. Confirmar la política de autorización correcta para cada uno de los 4 métodos (probablemente la misma secuencia `IdentityPrincipalPort → OrganizationBoundaryPolicy → RoleAuthorizationPolicy` ya usada en los métodos que sí funcionan — no inventar un mecanismo nuevo).
2. Aplicarla de forma idéntica al patrón ya existente en los métodos correctos del mismo servicio — no una solución distinta por método.
3. Escribir test de autorización positiva y negativa para cada uno de los 4 (mismo patrón que `HumanActorAuthorizationIntegrationTest`, pero verificando también que el estado NO se persiste en el caso de rechazo — la auditoría marcó como "decorativo" cualquier test que solo compruebe el tipo de excepción sin verificar ausencia de efectos).

**Criterio de éxito:** para cada uno de los 4 métodos, confirmación explícita (código real, no documento) de si tiene autorización o no. Los que no la tengan: test de autorización positiva + negativa, con verificación explícita de que un actor sin permiso no logra ningún efecto persistido (`verify(..., never())` o equivalente sobre la escritura).

**Responsable sugerido:** Core.

---

### A6. Contradicción interna en la propia "documentación actualizada" — camino de autorización E2E (PRIORIDAD ALTA, resolver junto con A2)

**Hallazgo:** `plan-ejecucion-agentes-fase5.md` (Tarea 5.9, nota arquitectónica) dice textualmente: *"la secuencia Boundary -> Role -> Aggregate no tiene actualmente un camino E2E ejecutable dentro de CommandService"*. En el mismo conjunto de documentos, `estado-fase5.md` marca **NUEVA-5 como COMPLETADA**, describiéndola como el cableado real de `IdentityPrincipalPort`/`OrganizationBoundaryPolicy`/`RoleAuthorizationPolicy` para `HumanActor` en los `*CommandService`.

**Por qué importa:** estas dos afirmaciones no pueden ser ambas ciertas al mismo tiempo. Si NUEVA-5 realmente completó el cableado E2E, la nota de la Tarea 5.9 es texto obsoleto que nadie actualizó. Si la nota de 5.9 sigue siendo cierta, NUEVA-5 no debería estar marcada "COMPLETADA" sin la salvedad explícita de que el camino E2E no es ejecutable todavía — es exactamente el mismo patrón de lenguaje inflado sin evidencia que ambas auditorías originales ya cazaron repetidamente en `estado-fase5.md`.

**Acción:** verificar contra el código real de al menos un `*CommandService` si el switch exhaustivo sobre `ActorRef` (para `HumanActor`) efectivamente invoca la secuencia completa `IdentityPrincipalPort → OrganizationBoundaryPolicy → RoleAuthorizationPolicy → Aggregate`, con un test que lo ejercite de punta a punta (no un mock que solo confirme que se llamó a un método).

**Criterio de éxito:** una sola afirmación verdadera, respaldada por test de integración real, sobre si el camino E2E de autorización humana funciona hoy o no — y la corrección del texto que quedó contradictorio.

**Responsable sugerido:** Core/Identity — mismo responsable que A2, puede resolverse en la misma revisión de código.

---

### A7. Hallazgos transversales nuevos, no capturados por ninguna de las dos auditorías (PRIORIDAD MEDIA)

**A7.1 — `CommandRetryTemplate` absorbe `RedundantDomainActionException` sin considerar el `commandId`.** Confirmado por la propia documentación de Fase 5 como hallazgo preexistente, sin corrección implementada. Riesgo real: dos comandos *distintos* (con `commandId` diferentes) que casualmente alcanzan el mismo estado redundante en el agregado podrían tratarse como "éxito" silencioso — la plantilla no distingue una repetición legítima del mismo comando de una coincidencia entre comandos distintos.

**A7.2 — Framework de reintento de proyecciones con riesgo de pérdida silenciosa de eventos.** Confirmado como `ABIERTO` en `hallazgo-framework-retry-projections.md`, afecta a `PendingAllocationProjectionHandler`. Sin corrección implementada.

**Acción:** ambos requieren diseño de corrección propio — no se resuelven como parte de A2/A6, son hallazgos independientes que necesitan su propia sesión de diseño con quien lidere Core.

**Criterio de éxito:** para A7.1, test que demuestre que dos `commandId` distintos alcanzando el mismo estado redundante NO se confunden como la misma operación. Para A7.2, el propio documento de hallazgo ya debería tener (o necesita) su criterio de cierre — verificar si existe.

**Responsable sugerido:** Core.

---

### A3. Outbox sin productor real de negocio (PRIORIDAD MEDIA-ALTA, bloquea la confianza en `STRICT` de Convocatoria)

**Hallazgo:** el mecanismo transaccional de Outbox existe y está probado en aislamiento, pero **ningún comando de negocio real** produce un mensaje — los 14 puntos de invocación pasan lista vacía. Solo `OutboxSagaCoordinator` construye mensajes, y únicamente para reconstruir reintentos/cuarentena de algo que ya debería haber existido.

**Por qué te importa para Fase 6:** ADR-033 (Convocatoria, diseñado en esta sesión) decide `STRICT` apoyándose explícitamente en "reutilizar el patrón Event Store + Outbox ya probado". Esa base es más frágil de lo asumido si nunca se ejercitó con tráfico de negocio real.

**Acción:**
1. Implementar el productor real más simple y ya identificado como pendiente: `ASSET_REGISTRATION_SAGA` (ya lo admite ADR-033 de Fase 5 explícitamente: "no existe actualmente un productor implementado").
2. Con ese primer productor real funcionando end-to-end (incluyendo entrega y consumo real, no solo persistencia del mensaje), confirmar con evidencia que el mecanismo sostiene carga real antes de que Convocatoria dependa de él para `STRICT`.
3. No avanzar la implementación de `STRICT` en Convocatoria hasta tener esta confirmación — coordinar con quien lidere esa pieza.

**Criterio de éxito:** al menos un flujo de negocio real (no un test aislado) produce, entrega y consume un `OutboxMessage`, con test que lo demuestre de extremo a extremo.

**Responsable sugerido:** Core/Sagas, coordinado con quien implemente Convocatoria.

---

### A4. ADR-028 a ADR-032 ausentes del repositorio (PRIORIDAD MEDIA)

**Hallazgo:** cinco documentos de decisión arquitectónica citados como aprobados por `estado-fase5.md` no existen físicamente. El propio documento se contradice: los da por "consolidados" en una sección y por "pendientes de incorporar" en otra.

**Por qué te importa para Fase 6:** ADR-034 (Identidad, diseñado en esta sesión) referencia la taxonomía de `ActorRef`/`HumanAccount` de "ADR-031" como ya cerrada. Si ese documento no existe formalmente, esa referencia no tiene respaldo verificable.

**Acción:**
1. Determinar si esas decisiones existen en algún otro formato (actas de reunión, comentarios de PR) que permita reconstruirlas fielmente, o si hay que redecidirlas desde cero.
2. Redactar los ADR faltantes con evidencia real de lo que el código ya implementa (no al revés — no adaptar el código a lo que "debería" haber dicho el ADR).
3. Resolver también la discrepancia de nombre `HumanActor` (código) vs. `HumanAccount` (documentación, incluido nuestro ADR-034) — decidir cuál es el nombre canónico y corregir el otro.

**Criterio de éxito:** los 5 ADR existen en el repositorio, con estado real verificado contra código, y la discrepancia de nombre queda resuelta en un solo sentido.

**Responsable sugerido:** quien lidere Identidad/documentación de Fase 5.

---

### A5. Manejo de errores silencioso (PRIORIDAD BAJA)

**Hallazgo:** `DonationProjectionHandler.java` tiene un `catch (Exception ignored) {}` que traga el tipo real de la excepción — un fallo real queda invisible.

**Acción:** reemplazar por manejo explícito, nombrando el tipo de excepción esperado y registrando (log) cualquier tipo inesperado en vez de descartarlo silenciosamente — mismo principio que ya aplicamos toda la sesión ("toda condición de fallo tiene su propia excepción nombrada").

**Criterio de éxito:** el `catch` genérico desaparece; existe un test que demuestra que un fallo real dentro de ese bloque es visible (log, métrica, o propagación), no silencioso.

**Responsable sugerido:** Core.

---

## Bloque B — IA / `ConvocatoriaAuditFacts` (ADR-036, cerrado parcialmente en esta sesión)

### B1. Contradicción de nomenclatura de puerto (BLOQUEANTE para cerrar el diseño)

**Hallazgo:** `ia-resumen.md` dice que `ConvocatoriaAuditFacts` usa `AuditFactsPort` ("mismo contrato, sin modificar"). `api-contract-matrix.md` §5 dice que el endpoint de narrativa de convocatoria usa `CampaignAuditFactsPort`, diseñado, implementación pendiente. Ninguna fuente declara si son la misma interfaz con nombre inconsistente o dos interfaces reales distintas.

**Acción:** verificar contra el código real de `contracts` (mismo tratamiento que se le dio a `FundCommandService`/`EventStorePort` en Blockchain) — no resolverlo por intención ni por cuál documento "parece" más autoritativo.

**Criterio de éxito:** una sola interfaz confirmada por código, con el otro nombre retirado formalmente de la documentación que lo citaba.

**Responsable sugerido:** quien lidere IA/Convocatoria — no es Blockchain.

---

### B2. Tres decisiones estructurales interdependientes, sin resolver (BLOQUEANTE para completar el diseño, no para empezar)

| Decisión | Pregunta | Estado |
|---|---|---|
| A — Recolección | ¿La porción de datos de `PhysicalAsset` se obtiene bajo demanda (pull) o vía proyección/change stream (push), como ya hace `DonationAuditFactsHandler`? | Abierta |
| B — Persistencia | ¿`ConvocatoriaAuditFacts` es efímero (se calcula y se descarta) o se persiste como snapshot histórico? | Abierta |
| C — Consistencia temporal | Al combinar tres fuentes de naturaleza distinta (`Convocatoria`/`Ledger` son CRUD, `PhysicalAsset` es event-sourced), ¿qué garantiza que los tres reflejen "el mismo momento"? | Abierta, es la más delicada de las tres |

**Por qué son interdependientes:** la solución de C depende de qué se decida en A (un mecanismo push facilita más una garantía de consistencia que uno pull puro); B es independiente en principio, pero si se decide snapshot histórico, probablemente conviene resolverlo en el mismo momento que se dispare la persistencia (p. ej. al cerrar la convocatoria), lo que vuelve a tocar A.

**Acción:** sesión de diseño dedicada (mismo formato Modo de Arquitectura que usamos para el resto de Fase 6), no resolverlo apresuradamente en medio de otro trabajo.

**Responsable sugerido:** quien lidere IA, con la misma disciplina de review de 12 puntos ya aplicada al resto de Fase 6.

---

### B3. Piezas de contrato faltantes (no bloqueantes para seguir diseñando, sí para implementar)

- **C5** — nombre/firma del productor de `ConvocatoriaAuditFacts`: no existe en ninguna fuente, hay que definirlo.
- **C6** — contrato de error si `campaignRef` no resuelve a ninguna `Convocatoria`: sin excepción nombrada.
- **C7** — caso `Convocatoria` sin `CampaignFundingLedger` correspondiente: posible hueco de ADR-033, no inventado aquí, hay que confirmarlo con quien lidere Convocatoria.
- **C8** — comportamiento de la narrativa pública cuando `Convocatoria.status=CLOSED`: pertenece al contrato del endpoint (ADR-037), no a este componente.

**Acción:** resolver en el mismo orden que aparecen — C5/C6 son del propio componente de IA; C7 requiere coordinación con Convocatoria; C8 requiere coordinación con APIs/Frontend.

---

### B4. Verificación técnica pendiente (no decisiones nuevas, solo confirmar con evidencia)

- Índice `{campaignRef:1, status:1}` sobre la proyección de `PhysicalAsset` usada para agregar — confirmar si ya existe.
- Trazabilidad `eventId`/`streamId` accesible desde esa misma proyección.
- Aislamiento de caché por campaña verificado, no asumido (dos campañas distintas no deben compartir accidentalmente una entrada de caché de narrativa).
- Single-flight verificado específicamente para narrativas de convocatoria concurrentes (el mecanismo ya existe para donación individual, hay que confirmar que cubre este caso nuevo también).

**Acción:** cuatro verificaciones puntuales, cada una con test dedicado, mismo estándar de evidencia que el resto de la sesión.

---

## Orden de ejecución recomendado

```
HOY, antes que cualquier otra cosa:
  Colisión de ADR (renumerar los 5 documentos de Fase 6 — es rápido y evita
  que se genere más trabajo referenciando números ambiguos)

Inmediato (esta semana):
  A1 (arregla el bloqueo de hoy mismo, libera Blockchain y el reactor completo)
  A2 + A6 (autorización + contradicción E2E — resolver juntos, misma revisión
  de código de FundCommandService/PhysicalAssetCommandService)

En paralelo, sin bloquear lo anterior:
  A4 (ADR faltantes — trabajo de documentación, no de código)
  A5 (catch silencioso — trivial, cualquier momento)
  A7 (hallazgos transversales — CommandRetryTemplate, proyecciones — sesión
  de diseño propia, no bloqueante para lo demás)
  B1 (contradicción de puerto — verificación de código, rápida)

Antes de avanzar la implementación de Convocatoria (STRICT):
  A3 (Outbox con productor real)

Cuando se retome el diseño de IA:
  B2 (las tres decisiones estructurales, sesión dedicada)
  B3, B4 (una vez resueltas las decisiones de B2)
```

## Checklist de cierre de este plan

Ningún ítem se marca como resuelto sin:
- [ ] Output literal de test (`Tests run: X, Failures: Y, Errors: Z`), no resumen del reactor ni cifra repetida de una ejecución anterior.
- [ ] Cita de código real para cualquier afirmación de "ya está corregido" — un documento de estado nunca es evidencia suficiente por sí solo, ya lo demostraron tres fuentes distintas hoy.
- [ ] Para A2/A3/A6/A7/B4 específicamente: test que demuestre el comportamiento negativo (qué pasa cuando algo *no* debería funcionar), no solo el camino feliz.
- [ ] Cualquier nueva "documentación de cierre" que llegue debe tratarse con el mismo escrutinio que las dos auditorías y este plan aplicaron a las anteriores — no asumir que una versión más reciente es automáticamente más confiable.
