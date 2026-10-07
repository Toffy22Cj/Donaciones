# Auditoría forense C-01 post-corrección

**Fecha:** 2026-10-02. **Rama:** `develop`, HEAD `673eda9`. Módulo `convocatoria` sin commit (untracked).
**Naturaleza:** informe temporal de auditoría adversarial. No es documentación canónica. No implementa ni propone correcciones.

Taxonomía: **A** código · **B** test/ejecución · **C** git · **D** documental · **E** inferencia · **F** contradicción · **G** no determinable.

---

## 1. Alcance

- Objetivo: intentar refutar R-1 (`CONFIRMED` sin fondos), R-2 (transacción externa que captura la excepción y confirma) y R-3 (puerto del ledger invocable sin barrera).
- Leído: `DonationIntentService`, `CampaignFundingLedgerService`, `ConvocatoriaTransactionRetryHelper`, `DonationIntent`, `DonationIntentStatus`, `CampaignFundingLedger`, `CampaignFundingLedgerRepositoryPort`, `DonationIntentRepositoryPort`, sus adaptadores Mongo, `ConfirmDonationIntentCommand`, `ConvocatoriaArchitectureTest`, y los tests `ConfirmDonationIntentIntegrationTest`, `CampaignFundingLedgerIntegrationTest`, `CloseConvocatoriaIntegrationTest`, `ConvocatoriaBusinessScenarioIntegrationTest`.
- Documentos: ADR-037, ADR-037 Enmienda 1, `implementation_plan.md`, `convocatoria-resumen.md`, `estado-fase6.md`, `golden-path.md`, `contract-wiring-review.md`, `api-contract-matrix.md`, `ADR-041`, `plan-api-fase6.md`, `revision-ready-api-fase6.md`, `fase-6-estructura-y-perimetro-convocatoria.md`, `documento-maestro-proyecto.md`, `auditoria-c01-idempotencia-fondos.md`.
- Ejecutado: una sonda propia (Q1–Q5) fuera del repositorio contra MongoDB 6.0 real (§8). No se ejecutó Maven.

**Hallazgo previo sobre el propio encargo (F):** R-1, R-2, R-3 y las sondas P1–P6 **no figuran** en `auditoria-c01-idempotencia-fondos.md`. Ese informe describe el estado **anterior** a la corrección (operación `applyFunds(campaignRef, amount)`, que ya no existe) y no usa esa numeración. Una búsqueda en `Documentos/` y en el disco no encuentra ningún otro registro de R-1..R-3 ni de P1–P6. Por eso este informe audita los hallazgos **tal como los formula el encargo** y los reproduce de forma independiente.

## 2. Estado del código inspeccionado

| Hecho | Evidencia | Clase |
|---|---|---|
| `applyFunds(campaignRef, amount)` ya no existe; la única entrada al incremento es `CampaignFundingLedgerService.applyFundsForIntent(ConfirmDonationIntentCommand)` (`CampaignFundingLedgerService.java:64`) | Lectura + `javap` sobre `target/classes` (actualizado: ningún `.java` de `main` es más reciente que el `.class`) | A |
| `applyFundsForIntent` lee la intención, llama a `confirmDonationIntent` y solo incrementa si este devuelve `true` (`:66-72`) | Lectura | A |
| `applyFundsForIntent` **no** tiene `@Transactional`. Sin transacción activa usa `transactionRetryHelper.executeWithRetry`; con transacción activa ejecuta en línea (`:74-76`) | Lectura | A |
| `confirmDonationIntent` sigue siendo `public`, con `@Transactional(SUPPORTS)` (`DonationIntentService.java:111-112`). Fuera de una transacción es una escritura de un solo documento | Lectura | A |
| `confirmDonationIntent` no consulta el ledger, no tiene `commandId` ni autorización (`confirmedBy` es un `String` libre) | Lectura | A |
| Llamadores de `confirmDonationIntent` en `main`: solo `CampaignFundingLedgerService:68`. En tests: `ConfirmDonationIntentIntegrationTest`, `CloseConvocatoriaIntegrationTest:131`, `ConvocatoriaBusinessScenarioIntegrationTest:224` | grep | A |
| Ningún módulo fuera de `convocatoria` lo referencia; `app` no depende de `convocatoria` (solo el `pom.xml` raíz lo lista como módulo) | grep | A |
| No hay controller, adaptador de entrada ni puerto de entrada en `convocatoria` | Listado de paquetes | A |
| Artefactos de Surefire del 2026-10-02 02:45 (posteriores a la corrección): `CampaignFundingLedgerIntegrationTest` 17/0/0, escenario 5/0/0, `CloseConvocatoriaIntegrationTest` 7/0/0 | `target/surefire-reports` (no re-ejecutados por esta auditoría) | B (artefacto previo) |

## 3. Semántica real de `DonationIntent.CONFIRMED`

**Respuesta: E — NO DETERMINABLE** como definición única. Las fuentes dan dos lecturas incompatibles a nivel de módulo.

| Fuente | Qué dice | Clase |
|---|---|---|
| ADR-037 §2.6 | Enumera `PENDING | CONFIRMED | FAILED | EXPIRED-UNKNOWN` sin definir `CONFIRMED`. El paso 3 del flujo (correlación válida → `clearFundsGenesis`) es lo que confirma el pago | D |
| Enmienda §5.3 [DECISIÓN] | `PENDING → CONFIRMED` es "**barrera antes de cualquier efecto financiero** sobre el ledger, en la misma transacción" | D |
| Enmienda §5.2 [DECISIÓN — NUEVA] N9 | "La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`" | D |
| Enmienda §5.2 N8 | Toda confirmación registra quién, cuándo, medio y referencia | D |
| `implementation_plan.md` §4.4 | Fila propia "Confirmar intención (transición) — intención `PENDING → CONFIRMED` condicional", como **transacción del módulo** separada | D |
| `implementation_plan.md` §1.3, §9.3 | El efecto sobre `Fund` está fuera de corte: "No invoca `clearFundsGenesis` ni toca `Fund`" | D |
| `ConfirmDonationIntentCommand` Javadoc | "La invocará el orquestador (fuera de corte). Firma provisional" | A (comentario) |
| `DonationIntentStatus` Javadoc | "En este corte solo se implementa `PENDING → CONFIRMED`" | A (comentario) |
| `CampaignFundingLedgerService` Javadoc | "C-01 (decisión humana del 2026-10-02): los fondos solo se aplican ligados a una `DonationIntent`… No existe aplicación de fondos sin intención" | A (comentario); la decisión no consta en ningún documento → G |
| Comportamiento del código | `CONFIRMED` = escritura condicional de `status` + datos N8. Independiente del ledger | A |

Lecturas posibles:

- **Nivel de diseño (D):** `CONFIRMED` es la barrera *dentro* de la unidad que aplica el dinero (ledger + `Fund` + outbox, ADR-037 §2.3). N9 asimila la confirmación manual a la génesis del `Fund`. Bajo esta lectura, `CONFIRMED` sin dinero aplicado no debería existir: opción **C**.
- **Nivel del primer corte (D + A):** el plan aprueba la transición como operación propia del módulo (§4.4) y los tests la ejercen así. `CONFIRMED` significa "transición registrada con evidencia N8": opción **A**.

**Limitación del propio planteamiento de R-1 (E):** si "fondos aplicados" significa lo que dice el diseño (`Fund.FUNDS_CLEARED` + ledger), **ningún** camino del corte actual puede cumplir `CONFIRMED ↔ fondos aplicados`, ni siquiera `applyFundsForIntent`, porque `Fund` está fuera de corte por decisión documentada. Lo más que el módulo puede garantizar es `CONFIRMED ↔ ledger incrementado`. Ningún documento enuncia esa invariante reducida.

## 4. Flujo canónico de confirmación

**Respuesta: C + D** — un flujo de `app` todavía no implementado, que compone barrera + ledger + `clearFundsGenesis` en una transacción. A nivel de módulo, **NO DETERMINABLE** si `confirmDonationIntent()` sigue siendo una operación permitida por sí sola.

| Pregunta | Respuesta | Clase |
|---|---|---|
| ¿`confirmDonationIntent()` es operación pública independiente? | El plan §4.4 y §9.4 la tratan como transacción y caso de uso propio del corte. Ningún documento posterior lo revoca | D |
| ¿`applyFundsForIntent()` reemplaza al anterior? | El Javadoc C-01 lo implica para el ledger ("no existe aplicación de fondos sin intención"), pero no dice nada sobre la confirmación sin fondos. Ningún documento lo registra | A (comentario) / G |
| ¿`confirmDonationIntent()` quedó obsoleto o interno? | Ninguna fuente lo dice. Sigue `public`, sin `@Deprecated`, y los tests lo usan como API | A / G |
| ¿Solo el webhook / orquestador confirma? | Enmienda §5.2: GATEWAY → webhook; BANK_TRANSFER → humano de la organización mediante `CLEAR_FUNDS_AS_GENESIS` (N9). `plan-api-fase6.md` E9 y `revision-ready-api-fase6.md`: el webhook vive en `app` | D |
| ¿Frontera Convocatoria / Payments? | La coordinación cross-módulo vive en `app` (ADR-037 §2). La transacción cruza módulos por infraestructura (`MongoTransactionManager` compartido), no por código (ADR-037 §2.3) | D |

No se infiere la obsolescencia de `confirmDonationIntent` por la aparición de `applyFundsForIntent`.

## 5. R-1 — ¿`CONFIRMED` sin fondos?

### Evidencia a favor

- **B (Q1):** `confirmDonationIntent` y luego `applyFundsForIntent`, en FLEXIBLE y en STRICT → `confirm=true`, `apply=false`, `status=CONFIRMED`, `cleared=0`.
- **B (Q2):** carrera real (`CyclicBarrier`) entre `confirmDonationIntent` y `applyFundsForIntent`, 20 rondas → `confirmWins=20`, `applyWins=0`, `errors=0`, **20/20 terminan `CONFIRMED` con ledger 0**. Mecanismo observado en el log: la transacción de `applyFundsForIntent` recibe `WriteConflict` (`TransientTransactionError`), el helper la reintenta, el reintento ve `CONFIRMED` y devuelve `false`. La escritura no transaccional de `confirmDonationIntent` gana siempre.
- **B (Q5):** STRICT con ledger ya en la meta (1000/1000): `confirmDonationIntent` sobre una intención de 300 → `CONFIRMED`. La intención queda confirmada aunque el ledger la habría rechazado entera (Enmienda §3.3). Después, `applyFundsForIntent` es un no-op, así que nunca llega a evaluarse la capacidad.
- **A:** `confirmDonationIntent` es `public`, inyectable y sin autorización ni `commandId`.
- **D:** Enmienda §5.3 sitúa la transición y el efecto financiero "en la misma transacción"; N9 equipara la confirmación manual a la génesis del `Fund`.
- **D (Javadoc de `applyFundsForIntent`):** "una intención ya confirmada es un no-op". Esa misma regla es la que vuelve irrecuperable el estado de Q1, Q2 y Q5: no existe operación que aplique los fondos de una intención ya `CONFIRMED`.

### Evidencia en contra

- **A:** hoy no hay ningún llamador de producción de `confirmDonationIntent` fuera de `applyFundsForIntent`, ni ningún adaptador de entrada. El escenario no es alcanzable desde producción **en este momento**.
- **D:** `implementation_plan.md` §4.4 aprueba "Confirmar intención (transición)" como transacción propia del módulo, y §9.4 exige `ConfirmDonationIntentIntegrationTest` sobre esa operación aislada.
- **B:** los tests del repositorio producen y aceptan `CONFIRMED` con ledger sin tocar. `ConfirmDonationIntentIntegrationTest` confirma intenciones de una convocatoria MONETARY con ledger y no comprueba el ledger. `ConvocatoriaBusinessScenarioIntegrationTest:222-224` comprueba `cleared == 0` y a continuación confirma con `confirmDonationIntent` (`true`). `CloseConvocatoriaIntegrationTest:131` hace lo mismo tras el cierre.
- **D:** el `Fund` está fuera de corte, así que `CONFIRMED` sin `Fund` es el estado normal de **toda** confirmación de este corte (§3).

### Intento de refutación

| Pregunta del encargo | Resultado |
|---|---|
| 1. ¿Contrato permite llamar a `confirmDonationIntent()` directamente? | Sí según el plan §4.4/§9.4 (D). Ningún documento lo prohíbe después de C-01 (G) |
| 2. ¿Caller de producción? | No (A) |
| 3. ¿Controller/port/adapter que lo exponga? | No (A) |
| 4. ¿La ausencia de callers cambia el contrato? | No. Ausencia de llamadores ≠ contrato (regla 15/16 del encargo) |
| 5. ¿Pública, interna o histórica? | Pública por plan y por código; "la invocará el orquestador" (Javadoc). No consta como histórica (G) |
| 6. ¿Regla que permita `CONFIRMED` sin fondos? | Ninguna explícita. Implícitamente, el plan §4.4 lo permite en el corte (D); el diseño §5.3 + N9 lo excluye (D) |
| 7. ¿Estado intermedio "confirmada pero pendiente de aplicación"? | No existe (`DonationIntentStatus`: `PENDING`, `CONFIRMED`, `FAILED`, `EXPIRED_UNKNOWN`) (A) |
| 8. ¿Mecanismo de recuperación? | No. `applyFundsForIntent` sobre `CONFIRMED` es no-op por diseño (A + B) |
| 9. ¿Tests consideran válido `CONFIRMED + ledger=0`? | Lo producen y no lo rechazan (B). No lo afirman como invariante deseada |
| 10. ¿Método anterior o posterior a la barrera? | Git: NO DETERMINABLE (sin historial). Metadatos de archivo: `DonationIntentService.java` modificado 2026-10-01 11:55; `CampaignFundingLedgerService.java` 2026-10-02 02:43, tras el informe C-01 (02:37). La fecha de modificación no prueba la fecha de creación (E) |

**No se consigue refutar R-1 como hecho técnico:** el estado es reproducible y además irrecuperable. **Tampoco se puede afirmar que viole el contrato**, porque el contrato vigente del corte (plan §4.4) aprueba la confirmación aislada y nadie ha decidido después si sigue permitida.

### Veredicto

**HALLAZGO VÁLIDO PERO REQUIERE DECISIÓN HUMANA** (DH-1, DH-2). No es falso positivo (B reproducible) ni bug confirmado (no existe regla canónica que el código incumpla de forma inequívoca).

## 6. R-2 — ¿La transacción externa puede romper atomicidad?

### Evidencia a favor

- **B (Q3):** STRICT, 900/1000. Dentro de `TransactionTemplate`: `applyFundsForIntent` sobre una intención de 200 → se captura `CampaignFundingLimitExceededException`; `status.isRollbackOnly()` antes del commit = **`false`**; el commit termina bien → intención **`CONFIRMED`**, `cleared=900`.
- **B (Q3-control):** el mismo escenario propagando la excepción → intención `PENDING`, `cleared=900`. La reversión depende enteramente de que el llamador propague.
- **A:** `applyFundsForIntent` no es un proxy transaccional y `confirmDonationIntent` (`SUPPORTS`) termina con normalidad antes del fallo. Nada marca la transacción como rollback-only.
- **F:** el Javadoc de `applyFundsForIntent` afirma sin condiciones: "Si el ledger rechaza la aplicación…, la confirmación se revierte con ella y la intención sigue `PENDING`". Q3 muestra que dentro de una transacción externa eso solo es cierto si el llamador propaga.
- **D:** la captura no es un patrón exótico. Enmienda §3.3 obliga a llevar el dinero rechazado por `STRICT`/`REJECT_EXCESS` a un registro de dinero no aceptable, y `implementation_plan.md` §14.1 (P1) sitúa "rechazo en el orquestador". Un orquestador que capture la excepción para escribir ese registro y confirmar es un diseño previsible (E). No consta si iría en la misma transacción (G).

### Evidencia en contra

- **A:** hoy no hay orquestador, webhook ni ningún llamador con transacción externa. El escenario no ocurre en el código existente.
- **B:** sin transacción externa, el módulo sí revierte la confirmación ante el rechazo (`strictRejectsWholeDonation…KeepsTheIntentPending`, `missingLedgerIsCampaignNotFoundAndRollsBackTheConfirmation`, escenario `:107-111`). Con transacción externa que **propaga**, también (`joinsAnExternalTransactionAndRollsBackWithIt`, Q3-control).
- **D:** el plan asigna al orquestador la responsabilidad transaccional (§7.2, §8 "requisito transaccional del orquestador", §13.3), y ese orquestador está fuera de corte.

### Intento de refutación

| Pregunta | Resultado |
|---|---|
| 1. ¿Caller real hoy? | No (A) |
| 2. ¿Caller previsto? | Sí: orquestador/webhook en `app` (ADR-037 §2.3; plan §8, §11; `plan-api-fase6.md` E9) (D) |
| 3. ¿Obligado a propagar? | Ninguna fuente lo exige (G) |
| 4. ¿Regla explícita de rollback? | Solo el plan §13.3: `appendAndOutbox` "se revierte junto con el ledger y la transición de la intención" (D). No dice qué pasa con un rechazo de capacidad capturado |
| 5. ¿La arquitectura prohíbe capturar? | No hay tal regla (G) |
| 6. ¿La transacción debe abarcar intención + ledger? | Sí: Enmienda §5.3, ADR-037 §2.3 (D) |
| 7. ¿Mismo módulo o frontera? | Frontera de código entre módulos, transacción compartida por infraestructura (ADR-037 §2.3) (D) |
| 8. ¿La excepción exige rollback por semántica de negocio? | `REJECT_EXCESS` rechaza la donación completa (Enmienda §3.3) (D). Qué estado debe tener la intención tras el rechazo no está definido: hoy queda `PENDING` (A). `FAILED`/`EXPIRED_UNKNOWN` son del webhook, P3 (A, Javadoc) |
| 9. ¿Qué ocurre cuando los fondos no pueden aplicarse? | El dinero va al registro de dinero no aceptable; mecanismo P1 pendiente (D). El destino de la `DonationIntent` no está escrito en ningún sitio (G) |

El escenario **no es imposible por contrato**: ninguna regla lo prohíbe y el diseño del rechazo (P1) lo hace previsible. Tampoco es un bug de código existente, porque no hay llamador.

### Veredicto

**HALLAZGO VÁLIDO PERO REQUIERE DECISIÓN HUMANA** (DH-3, DH-4): es una dependencia del orquestador futuro con su contrato sin definir. La afirmación incondicional del Javadoc es una contradicción (F) entre comentario y comportamiento, aunque no rompe ningún flujo hoy.

## 7. R-3 — ¿El ledger puede saltarse la barrera?

### Evidencia a favor

- **B (Q4):** `CampaignFundingLedgerRepositoryPort.incrementUnconditionally(campaignRef, 250)` con la intención `PENDING` → `true`, `cleared=250`, intención `PENDING`.
- **A:** el puerto es una interfaz `public`, bean de Spring. Ninguna regla ArchUnit (`ConvocatoriaArchitectureTest`) restringe quién lo consume; solo protege la frontera hacia otros módulos y la pureza del dominio.

### Evidencia en contra

- **A:** está en `application.port.out` y su Javadoc lo define como "Persistencia de `CampaignFundingLedger`". Es un puerto de salida (persistencia), no un caso de uso.
- **A:** consumidores reales: `CampaignFundingLedgerService` (incrementos, solo detrás de la barrera) y `ConvocatoriaLifecycleService` (`insert`/`deleteByCampaignRef`, operaciones legítimas sin intención: creación del ledger con N2 y retirada con G1). Ningún otro.
- **A:** ningún módulo fuera de `convocatoria` depende de él; `app → convocatoria` no existe (B-1).
- **E:** cualquier componente capaz de inyectar el puerto puede inyectar igual `MongoTemplate` y escribir la colección directamente. Que un bean sea invocable no define un flujo de negocio autorizado (regla 16 del encargo).
- **D:** plan §8: la operación de aplicación de fondos se invoca "solo por el orquestador de `app`". Se refiere al servicio, no al puerto.

### Intento de refutación

| Pregunta | Resultado |
|---|---|
| 1. ¿Contrato público del dominio? | No: puerto de salida de persistencia (A) |
| 2. ¿Puerto interno? | Sí por ubicación y Javadoc (A). No existe regla que lo haga cumplir (A) |
| 3. ¿Quién puede invocarlo según arquitectura? | Las capas de aplicación del módulo (convención hexagonal del plan §4.3) (D/E). Sin regla escrita más fina (G) |
| 4. ¿Caller real fuera de `CampaignFundingLedgerService`? | Solo `ConvocatoriaLifecycleService`, y sin incrementos (A) |
| 5. ¿Alcanzable desde producción legítima? | No (A) |
| 6. ¿Bean invocable ⇒ contrato lo permite? | No |
| 7. ¿Operaciones legítimas sin `DonationIntent`? | Sí, `insert` y `deleteByCampaignRef`. Ningún incremento legítimo sin intención: el Javadoc C-01 lo excluye y `CASH` queda sin camino hasta P5 (A) |

### Veredicto

**BYPASS NO RELEVANTE AL CONTRATO.** Sobre la conclusión R-3 la refutación tiene éxito: la sonda demuestra una capacidad técnica de cualquier código con acceso a la persistencia, no un camino legítimo. Queda como observación, no como condición de C-01, que no hay ninguna regla automática que impida a futuras clases de `application` usar el puerto directamente.

## 8. Auditoría de las sondas P1-P6

Las sondas P1–P6 **no son auditables**: no constan en el informe citado ni en ningún archivo del repositorio, y en el disco no aparece ningún fuente ni log de ellas (§1). Su construcción, infraestructura y transacciones son **NO DETERMINABLES**. El informe anterior sí cita otra sonda (`C01FundsIdempotencyProbeTest`), pero apuntaba a `applyFunds`, que ya no existe, así que no aplica al código actual.

Se sustituyen por una sonda propia, temporal y fuera del repositorio:

- **Fuente:** `scratchpad/probe2/src/PostCorrectionProbe.java`; log en `scratchpad/probe2.log`.
- **Infraestructura:** MongoDB 6.0 en replica set (Testcontainers). Contexto `ConvocatoriaTestApplication` con los fakes de test del módulo. Servicios, adaptadores e índices de producción (`target/classes`, sin recompilar el módulo). Índices creados con `ConvocatoriaTestIndexes`.

| Sonda | Qué intenta demostrar | ¿Es flujo legítimo? | Resultado | Evidencia | ¿Refuta/Confirma? |
|---|---|---|---|---|---|
| Q1 | R-1 secuencial: confirmar y luego aplicar | Depende de DH-1: legítimo según plan §4.4; sin llamador hoy | `CONFIRMED`, `cleared=0` (FLEXIBLE y STRICT) | B | Confirma el hecho técnico |
| Q2 | R-1 en carrera (20 rondas) | Igual que Q1 | 20/20 `CONFIRMED` + ledger 0; la confirmación aislada gana siempre (WriteConflict → reintento → no-op) | B | Confirma, y muestra que el resultado es determinista, no una carrera rara |
| Q3 | R-2: transacción externa captura y confirma | Previsible (orquestador + P1), no existe hoy | `rollbackOnly=false`, commit OK, `CONFIRMED`, `cleared=900` | B | Confirma la posibilidad técnica |
| Q3-control | Lo mismo propagando | Sí (patrón documentado) | `PENDING`, `cleared=900` | B | Delimita R-2: depende del llamador |
| Q4 | R-3: incremento directo del puerto | No (no hay camino legítimo) | `cleared=250` con intención `PENDING` | B | Posibilidad técnica, sin relevancia contractual |
| Q5 | R-1 con capacidad agotada | Igual que Q1 | STRICT 1000/1000 → intención de 300 `CONFIRMED` | B | Agrava R-1: la confirmación aislada ignora `STRICT`/`REJECT_EXCESS` |

Las sondas usan el reloj fijo de test y una organización verificada por el fake (X1). Ninguna de las dos cosas afecta a las rutas medidas (E).

## 9. Git / evolución histórica

| Comprobación | Resultado | Clase |
|---|---|---|
| `git log --all --oneline -- convocatoria` | Vacío: el módulo nunca se ha versionado | C |
| `git log --all -S applyFunds` / `-S confirmDonationIntent` | Vacío | C |
| Historial de `implementation_plan.md`, Enmienda 1 y los informes de auditoría | Vacío: sin seguimiento | C |
| `git diff pom.xml` | Solo añade `<module>convocatoria</module>` | C |
| `git diff estado-fase6.md` | §3bis registra el primer corte (168 tests, 2026-10-01). **No menciona C-01 ni `applyFundsForIntent`** | C |
| Cuándo apareció cada método, si hubo migración incompleta, commit que explique el diseño | **NO DETERMINABLE** por Git. Los metadatos del sistema de archivos (no Git) sitúan `CampaignFundingLedgerService.java` y `CampaignFundingLedgerIntegrationTest.java` el 2026-10-02 02:43–02:44, tras el informe C-01 (02:37), y `DonationIntentService.java` el 2026-10-01 | G / E |

La lectura de los archivos apunta a que la corrección C-01 sustituyó `applyFunds` por `applyFundsForIntent` y dejó `confirmDonationIntent` sin cambios. Es una inferencia sin respaldo de Git (E).

## 10. Contradicciones encontradas

| # | Contradicción | Fuentes | Clase |
|---|---|---|---|
| F-1 | El encargo atribuye R-1..R-3 y P1–P6 a `auditoria-c01-idempotencia-fondos.md`, que no los contiene y describe el código anterior a la corrección | Encargo vs. informe | F |
| F-2 | Javadoc de `applyFundsForIntent`: "la confirmación se revierte con ella" (sin condición) vs. Q3, donde dentro de una transacción externa no se revierte si el llamador captura | `CampaignFundingLedgerService.java:55-58` vs. B | F |
| F-3 | Plan §4.4 (confirmación como transacción propia del módulo) vs. Enmienda §5.3 y N9 (confirmación en la misma transacción que el efecto financiero / la génesis) | D vs. D | F (de alcance: puede leerse como "primitiva del corte" frente a "flujo final", pero ningún documento lo dice) |
| F-4 | Javadoc C-01 cita una "decisión humana del 2026-10-02" que no consta en `convocatoria-resumen.md`, `estado-fase6.md`, `implementation_plan.md` ni en la Enmienda | Código vs. documentación | F / G |
| F-5 | `estado-fase6.md` §3bis presenta el corte como verificado con 168 tests y no recoge la corrección C-01 ni su suite (los artefactos de Surefire posteriores existen) | D vs. B (artefacto) | F (desactualización) |
| F-6 | `api-contract-matrix.md:41` y `golden-path.md:33` presentan "ledger update, misma transacción" con `clearFundsGenesis` como contrato, mientras el plan lo deja fuera de corte (ya señalado en el informe anterior) | D vs. D | F (ya conocida; N11 da precedencia a ADR-037 + Enmienda) |

## 11. Decisiones humanas pendientes

| Decisión | Pregunta exacta | Fuentes implicadas | Por qué no puede resolverse automáticamente |
|---|---|---|---|
| DH-1 | ¿Sigue siendo `DonationIntentService.confirmDonationIntent()` una operación permitida por sí sola (sin aplicar fondos), o la confirmación de una intención MONETARY solo puede ocurrir dentro de la unidad que aplica los fondos? | Plan §4.4, §9.2, §9.4; Enmienda §5.3, N9; Javadoc C-01 de `CampaignFundingLedgerService`; `ConfirmDonationIntentCommand` ("la invocará el orquestador") | El plan del corte la aprueba como transacción propia; el diseño y la decisión C-01 apuntan a lo contrario; ninguna fuente posterior a C-01 resuelve su estatus |
| DH-2 | ¿Qué significa `CONFIRMED`: "transición registrada con evidencia N8", "importe aplicado al ledger" o "importe aplicado al ledger y `Fund` génesis creado"? ¿Qué invariante debe garantizar el módulo `convocatoria` por sí solo mientras `Fund` esté fuera de corte? | ADR-037 §2.6, §2.3; Enmienda §5.2 (N8, N9), §5.3; plan §1.3, §9.3 | Ningún documento define el estado. N9 lo liga al `Fund`, que está fuera de corte; la invariante reducida (`CONFIRMED ↔ ledger`) no está escrita en ningún sitio |
| DH-3 | Cuando el ledger rechaza una intención (`STRICT`, `REJECT_EXCESS`), ¿qué estado debe quedar en la `DonationIntent` (`PENDING`, `FAILED`, otro) y en qué transacción se escribe el registro de dinero no aceptable respecto de la transición? | Enmienda §3.3, §8 P1; plan §14.1; `DonationIntentStatus` Javadoc (`FAILED` reservado a P3) | P1 está pendiente de forma explícita y la Enmienda prohíbe convertir un pendiente en decisión implícita |
| DH-4 | ¿Quién garantiza la reversión de la confirmación ante un fallo de aplicación dentro de una transacción externa: el módulo (sin depender del llamador) o el orquestador (obligado a propagar)? | Plan §7.2, §8, §13.3; Enmienda §6; informe C-01 §9 ("esa elección es humana") | El diseño asigna la composición al orquestador, pero no fija sus obligaciones ante excepciones de dominio; el informe anterior ya la dejó como decisión humana |
| DH-5 | ¿Dónde consta la decisión C-01 del 2026-10-02 citada en el Javadoc y cuál es su alcance exacto (solo "ledger detrás de la barrera", o también "no hay confirmación sin fondos")? | `CampaignFundingLedgerService` Javadoc; `CampaignFundingLedgerIntegrationTest` Javadoc; `auditoria-c01-idempotencia-fondos.md` §9 | La decisión solo consta en comentarios de código; el repositorio no permite verificar su contenido ni su aprobación |

## 12. Estado de C-01

**`BLOQUEADO POR DECISIÓN HUMANA`**

| Condición de cierre | Estado | Evidencia |
|---|---|---|
| 1. Sin doble aplicación por intención | ✅ Cumplida para `applyFundsForIntent`: secuencial, concurrente y tras reinicio | B (suite del repositorio; Q2 tampoco produjo doble aplicación) |
| 2. Ninguna intención `CONFIRMED` sin fondos | ❌ No cumplida (Q1, Q2, Q5); si es exigible depende de DH-1/DH-2 | B + G |
| 3. Sin estado parcial irreversible tras un fallo | ⚠️ Cumplida sin transacción externa o propagando; no cumplida si el llamador captura (Q3); depende de DH-3/DH-4 | B + G |
| 4. Ningún camino legítimo se salta las invariantes del ledger | ✅ Cumplida (R-3 no es un camino legítimo) | A |
| 5. Concurrencia relevante cubierta | ⚠️ Cubierta entre aplicaciones; la carrera confirmar ∥ aplicar no está cubierta y siempre deja huérfano (Q2); su relevancia depende de DH-1 | B |
| 6. Semántica de `CONFIRMED` documentada | ❌ No (DH-2) | D / G |
| 7. Flujo canónico único o flujos diferenciados | ❌ No (DH-1) | D / G |

El defecto original (duplicación de fondos) está corregido. Las condiciones 2, 3, 5, 6 y 7 no fallan por un error de código frente a una regla clara: fallan porque la regla no existe o se contradice. Por eso el estado no es `BUG CONFIRMADO` ni `CERRADO`.

## 13. Resumen ejecutivo

1. La duplicación original de C-01 está corregida: `applyFundsForIntent` aplica como mucho una vez por intención.
2. R-1, R-2 y R-3 no aparecen en el informe citado; se reprodujeron de forma independiente contra MongoDB real.
3. **R-1 sobrevive como hecho:** `confirmDonationIntent` deja `CONFIRMED` con ledger 0, también en carrera (20/20) y aunque el ledger habría rechazado la donación (Q5). El estado es irrecuperable.
4. **R-1 no se puede declarar bug:** el plan del corte aprueba la confirmación aislada y nadie la ha revocado → requiere decisión humana.
5. Si "fondos aplicados" incluye el `Fund` (diseño + N9), ningún camino del corte puede cumplir la invariante; la invariante reducida `CONFIRMED ↔ ledger` no está documentada.
6. **R-2 sobrevive como posibilidad técnica** (Q3: capturar y confirmar deja `CONFIRMED` sin fondos, sin marca de rollback). No hay llamador hoy; el diseño de P1 lo hace previsible → requiere decisión humana.
7. El Javadoc de `applyFundsForIntent` promete una reversión incondicional que Q3 desmiente (F-2).
8. **R-3 queda refutado como violación de contrato:** el puerto es de persistencia, sin llamadores ilegítimos; la sonda prueba una capacidad técnica, no un camino.
9. La decisión C-01 del 2026-10-02 solo consta en comentarios de código; `estado-fase6.md` no refleja la corrección.
10. **C-01 = BLOQUEADO POR DECISIÓN HUMANA** (DH-1 a DH-5).

---

**Archivos modificados por esta auditoría:** solo este informe. Código de producción: 0. Tests: 0. POMs: 0. ADRs: 0. Documentación canónica: 0. Temporales fuera del repositorio: `scratchpad/probe2/` y `scratchpad/probe2.log`, `scratchpad/cp.txt`. Efecto colateral: contenedor MongoDB efímero de Testcontainers. No se ejecutó Maven sobre el módulo; `target/` no se modificó.

---

## 14. Reauditoría contra la decisión humana DH-1–DH-5 (2026-10-02)

Decisión registrada en `convocatoria-resumen.md` §6.17 y `implementation_plan.md` rev. 2.3 (§7.2, §9.2) **antes** de tocar código. Código no modificado: la reauditoría encontró dos contradicciones (C01-A, C01-B) y, por la regla 7 de la instrucción y la regla 2.1 de `reglas-equipo-y-agentes.md`, se detiene antes de cambiar el comportamiento.

### 14.1 Hallazgos frente a la decisión

| Hallazgo | Con DH-1–DH-5 | ¿Bug real? |
|---|---|---|
| R-1 — `CONFIRMED` con ledger 0 tras `confirmDonationIntent()` | Estado válido por DH-1 y DH-2 | **No.** Pero genera la contradicción C01-A |
| R-1 / Q5 — confirmación aislada sobre STRICT llena | Válido por DH-2: no se intentó ninguna aplicación de fondos, así que DH-3 no aplica | No |
| R-2 — un orquestador captura `CampaignFundingLimitExceededException` y confirma | Lo prohíbe DH-4 al llamador; el módulo propaga la excepción (B, Q3-control). DH-4 descarta trasladar la defensa a `convocatoria` | **No es bug del módulo.** Queda una afirmación falsa en el Javadoc de `applyFundsForIntent` (F-2), pendiente de corregir |
| R-2 sin transacción externa o propagando | Intención `PENDING`, ledger intacto (B) | Cumple DH-3 |
| R-3 — puerto del ledger invocado directamente | Sin cambios | No |
| Javadoc "decisión humana del 2026-10-02" en `CampaignFundingLedgerService` y `CampaignFundingLedgerIntegrationTest` | DH-5: no constaba en ningún documento | Referencia incorrecta, pendiente de corregir |

### 14.2 Contradicciones que detienen la implementación

**C01-A — DH-1/DH-2 frente a la Enmienda §5.3 y el código.**
- Enmienda §5.3 [DECISIÓN] y el código usan la transición `PENDING → CONFIRMED` como **única** barrera e idempotencia del efecto sobre el ledger. `applyFundsForIntent` devuelve `false` y no aplica nada sobre una intención `CONFIRMED` (`CampaignFundingLedgerService.java:68-70`).
- DH-1 permite la transición sin efecto, y DH-2 dice que `CONFIRMED` no implica fondos aplicados.
- Resultado: una intención confirmada por `confirmDonationIntent()` queda sin ningún camino para aplicar su importe, y el `false` es indistinguible de "ya aplicado" (B: Q1, Q2 —20/20 en carrera—, Q5).
- No se puede resolver sin una de estas cosas, ninguna cubierta por la decisión: restringir la confirmación aislada (choca con DH-1); otra marca de "fondos aplicados" (estado o campo nuevo: lo prohíbe DH-3, y desplaza la barrera de §5.3); o aceptar expresamente que esa intención no recibe fondos en el corte (regla 2.6: un estado sin salida necesita decisión explícita).

**C01-B — DH-4/DH-5 frente a `implementation_plan.md` §7.2, §8 y §4.4 y el código.**
- El plan aprobado asigna al orquestador de `app` la regla "solo toca el ledger si la transición se aplicó en la misma transacción". §4.4 no contiene una transacción combinada transición + ledger.
- El código compone ambas cosas dentro de `convocatoria` (`applyFundsForIntent`) y lo justifica con la decisión no registrada de DH-5.
- DH-4 dice que la composición del flujo externo se queda donde marca la arquitectura documentada y que no se traslada a `convocatoria`. No dice si el acoplamiento **intra-módulo** transición + ledger es parte de esa composición.
- Hecho relevante: retirar el acoplamiento del módulo reabre la doble aplicación por intención para cualquier llamador que no componga la barrera (B, `auditoria-c01-idempotencia-fondos.md` §6).

### 14.3 Correcciones identificadas, no aplicadas

Todas tocan `CampaignFundingLedgerService` o sus tests, y su redacción depende de C01-A y C01-B:

- Javadoc de `applyFundsForIntent`: la reversión ante un rechazo del ledger dentro de una transacción externa depende de que el llamador propague (F-2, DH-4). Hoy el comentario la afirma sin condición.
- Javadoc de clase de `CampaignFundingLedgerService` y de `CampaignFundingLedgerIntegrationTest`: sustituir la decisión no registrada por `convocatoria-resumen.md` §6.17 (DH-5).
- Javadoc de `confirmDonationIntent` / `DonationIntentStatus`: `CONFIRMED` no afirma fondos aplicados (DH-1, DH-2).
- Tests para los invariantes cerrados:
  - confirmación aislada → ledger sin cambios, con aserción negativa sobre los dos incrementos (DH-1, DH-2);
  - rechazo del ledger dentro de una transacción externa que propaga → excepción visible, intención `PENDING`, ledger sin cambios (DH-3, DH-4).

### 14.4 Estado de C-01

**`BLOQUEADO POR DECISIÓN HUMANA`**, ahora solo por C01-A y C01-B. DH-1 a DH-5 cierran la semántica de `CONFIRMED` (condición 6), el comportamiento ante rechazo (condición 3, sujeta a DH-4) y R-2/R-3. Faltan por cerrar la condición 7 (flujos permitidos, C01-A) y la ubicación del acoplamiento (C01-B). Tests y sondas no se re-ejecutaron porque no hubo cambios de código.

---

## 15. Cierre (2026-10-02)

Las secciones anteriores se conservan como registro. La decisión final está en `convocatoria-resumen.md` §6.18 y sustituye los puntos abiertos C01-A y C01-B de §14 y de §6.17. Corrección aplicada: `@Transactional(propagation = SUPPORTS)` en `CampaignFundingLedgerService.applyFundsForIntent`, más la corrección de los Javadocs (DH-5) y 4 tests nuevos. Verificación: reactor completo `BUILD SUCCESS` (convocatoria 178 tests), y sonda adversarial V1–V7d (`scratchpad/probe3/`), donde un llamador que captura el rechazo ya no puede confirmar la transacción (`UnexpectedRollbackException`, intención `PENDING`).
