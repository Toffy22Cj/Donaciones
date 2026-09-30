# Verificación del cierre de huecos — ADR-038 (Identidad: Platform Administrator)

**Fecha:** 2026-09-30 · **Estado:** Evidencia. Las 9 decisiones de la §5 fueron **aprobadas por el equipo el 2026-09-30** e incorporadas a `ADR-038` y a `ADR-026-enmienda-desactivacion-platform-administrator.md`. Este informe no contiene código de producción.
**Objeto:** poner a prueba las propuestas de Claude y de ChatGPT para los 13 huecos de §7, más los huecos nuevos que aparecieron.

> **Aviso de numeración.** Lo que en el debate se llamó "ADR-034" es, en el repositorio, el **ADR-038**
> (`Documentos/ADR-038-identidad-platform-administrator-verificacion-organization.md`). El ADR-034 real es
> `read-model-pending-allocation`. Las referencias a "ADR-033" dentro del texto debatido corresponden al **ADR-037**
> (Convocatoria). Este informe usa la numeración del repositorio.

---

## 1. Qué se verificó, cómo, y qué NO se pudo verificar

| Tipo de evidencia | Fuente exacta |
|---|---|
| Código real del proyecto | `Toffy22Cj/Donaciones`, rama `feat/contracts-campaign-auditfacts-port`, commit `9c05824` (2026-09-29), la más reciente |
| Versiones reales | Spring Boot 3.4.4 → driver MongoDB **5.2.1** y Spring Data BOM **2024.1.4** (spring-data-mongodb **4.4.4**), leído en `spring-boot-dependencies/build.gradle` de la etiqueta `v3.4.4` |
| Comportamiento de librerías | Código fuente de `spring-data-mongodb` etiqueta `4.4.4` y `mongo-java-driver` etiqueta `r5.2.1` |
| Semántica del servidor | Manual oficial de MongoDB, *Production Considerations → In-progress Transactions* |
| Diseños concurrentes | Verificador exhaustivo de intercalaciones (`modelcheck.py`), **52.016 intercalaciones** en 9 escenarios |

**Límite que hay que decir claramente.** La política de red de la sesión de verificación bloqueó Docker Hub y Maven Central (HTTP 403 del proxy de salida). Por eso:

- **No se ejecutó MongoDB real ni `mvn test`.** No hay salida de Surefire en este informe y no se afirma ningún test "en verde".
- El verificador de intercalaciones es un **modelo** de la semántica documentada de MongoDB, no el motor. Encuentra diseños rotos (contraejemplos concretos) y confirma diseños bajo esa semántica; **no sustituye** los tests de Testcontainers de la §6, que siguen siendo la *Definition of Done*.

Reglas del modelo, tomadas del manual oficial:
- las lecturas dentro de una transacción ven una instantánea y pueden ser obsoletas;
- una escritura adquiere el bloqueo del documento **solo si lo modifica**;
- escribir un documento modificado tras la instantánea, o bloqueado por otra transacción en curso, aborta con *WriteConflict* (`TransientTransactionError`);
- se reintenta la transacción completa hasta 3 veces, igual que `MongoTransactionRetryHelper`.

---

## 2. Hallazgos en el código real que afectan al ADR-038 (anteriores a los 13 huecos)

Ninguna de las dos IAs podía ver esto, porque ninguna tenía el repositorio.

| # | Hallazgo | Evidencia | Consecuencia |
|---|---|---|---|
| H1 | **El ADR-038 §2.1 reabre en silencio una decisión del ADR-035.** El ADR-035 (Approved) definió la variante humana como `HumanActor` y **rechazó explícitamente** meter organización y roles en ella. El ADR-038 la llama `HumanAccount` con `accountId + organizationId + roles`. | `core/.../event/HumanActor.java` = `record HumanActor(String accountId)`; `ActorRef permits SystemActor, ExternalActor, HumanActor`; ADR-035 §Alternativas | Es el patrón que `reglas-equipo-y-agentes.md` §2.4 prohíbe. **Hay que decidir** entre enmendar el ADR-035 o corregir el §2.1 del ADR-038 para alinearlo con `HumanActor(accountId)`. |
| H2 | La firma del puerto no es la que el ADR-038 §2.6 declara "verificada en la fuente". | `contracts/.../IdentityPrincipalPort.java`: `resolvePrincipal(String accountId)`, no `resolve(accountId)` | Corregir el ADR. |
| H3 | **`resolvePrincipal` no rechaza cuentas `INACTIVE`**: devuelve un principal completo con roles. Para cuentas sin organización devuelve el principal "vacío" que `identity-resumen.md` §5 prohíbe. | `IdentityPrincipalPortImpl.resolvePrincipal` no consulta `status` | El ADR-038 describe como existente algo no implementado. Es **requisito de seguridad** antes del filtro JWT: una cuenta desactivada seguiría autorizada. |
| H4 | **El "actor propio de `identity`" (ADR-038 §2.2) no existe.** `AuditLogEntry.actorAccountId` es un `AccountId` obligatorio. | `identity/domain/model/AuditLogEntry.java` | El bootstrap (#6) **no se puede auditar** sin cambiar este tipo. |
| H5 | **El Audit Log actual registra como actor al propio objetivo** (`actorAccountId = accountId`). Dice, por ejemplo, que cada cuenta se desactivó a sí misma. | `DeactivateAccountService`, `AssignAdministratorService` | Hoy el Audit Log no es fiable en el campo "quién". Es el mismo tipo que H4. |
| H6 | ADR-026 y `estado-fase4.md` dicen que en `identity` los errores transitorios se reintentan 2–3 veces, pero **7 de 11 servicios usan `@Transactional` sin reintento**, incluido `DeactivateAccountService`. | `grep executeWithRetry` → solo 4 servicios | Discrepancia no documentada como deuda. Afecta al hueco A: un conflicto en `Deactivate` sale al usuario como error crudo. |
| H7 | **No existe nada de Platform Administrator en código**: ni `platformAuthority`, ni `PlatformAuthorityState`, ni `version`. | Búsqueda en todo el repositorio | Cierra la duda de #9: no hay implementación previa basada en `version` que respetar. |
| H8 | `AuthorizationPrincipal` hoy no tiene `platformAuthority`. | `contracts/.../AuthorizationPrincipal.java` | Añadirlo **cambia un contrato de `contracts` usado por `core`**. Por §3.5 de las reglas, el ADR-038 debe declararlo explícitamente como cambio de puerto. |

---

## 3. Veredicto hueco por hueco

Leyenda: **Confirmado** = evidencia lo respalda · **Refutado** = evidencia lo contradice · **Modelo** = resultado del verificador · **Fuente** = código de librería o manual.

### #1 / #2 — `GRANT` duplicado, `REVOKE` sin autoridad
**Propuesta: excepción determinista** (`PlatformAuthorityAlreadyGrantedException` / `PlatformAuthorityNotHeldException`). Ambas IAs coinciden.
- **Precedente en contra, confirmado en código**: `AssignAdministratorService` y `Account.deactivate()` son no-op idempotentes. El ADR-038 debe justificar por qué difiere: `GRANT` y `REVOKE` mueven un contador global y un no-op obligaría a probar que no lo toca.
- **Modelo**: con la precondición evaluada dentro de la transacción, las dos opciones conservan los invariantes. Es una **decisión de producto**, no de corrección.

### #3 — `GRANT` + `REVOKE` concurrentes sobre la misma cuenta
**Confirmado por el modelo (S3, 210 intercalaciones, 0 contraejemplos)**, con el diseño de Claude y con el de ChatGPT. El orden de *commit* decide y el perdedor reevalúa.

### #4 — Verificación sobre `VERIFIED` / `REJECTED`
Sin disputa. `InvalidVerificationTransitionException(currentStatus, command)`, sin mutación ni auditoría.

### #5 — Bootstrap
**Modelo S6 (812 intercalaciones)**: dos bootstraps concurrentes → exactamente uno gana, con las dos propuestas.

**Modelo S7 — las DOS propuestas se rompen.** Si ya existe una cuenta con `platformAuthority` pero falta `PlatformAuthorityState` (la situación del hueco #8), el bootstrap crea `count=1` con 2 administradores reales.
- La comprobación de ChatGPT solo mira la cuenta destino y no basta.
- **Corrección**: el bootstrap exige además que **ninguna** cuenta tenga `platformAuthority`. Si alguna la tiene, falla (fail-closed).
- No hay carrera posible con `GRANT`: sin estado, `GRANT` falla por #8.
- Sin esta regla, el bootstrap sería una vía de "auto-reparación" encubierta, justo lo que #8 prohíbe.

### #6 — Auditoría del bootstrap
- **Refutado — ChatGPT**: propuso reutilizar `SystemActor`. Está en `core.domain.event.SystemActor` (confirmado en código); usarlo desde `identity` viola el ADR-038 §2.2 y la frontera `identity ↛ core`.
- **Incompleto — Claude**: propuso una variante de "actor propio de `identity`" que **no existe** (H4).
- **Decisión necesaria**: cambiar `AuditLogEntry.actorAccountId` por un tipo de actor propio de `identity` (por ejemplo, cuenta autenticada o sistema). Ese mismo cambio resuelve H5, que hoy registra un actor falso.

### #7 — Fallo de `TokenIssuerPort.issue()`
Sin disputa. `TokenIssuanceException` → 500, nunca 401. No verificable hoy: no existe `api`/JWT de login en código.

### #8 — `PlatformAuthorityState` ausente
Sin disputa. Fail-closed, sin auto-reparación. Hay que completarlo con la regla de #5.

### #9 — Mecanismo de concurrencia
**Refutado — la crítica principal de ChatGPT tal como se formuló.** Afirmó que el `$inc` de Claude podía inflar el contador.
- **Modelo S2 (4.326 intercalaciones)**: el diseño de Claude da **0 contraejemplos**, con resultados idénticos al de ChatGPT.
- El motivo: todo `GRANT` y `REVOKE` escribe en el mismo singleton, así que dos cualesquiera entran en conflicto, y el perdedor relee y reevalúa.

**Donde ChatGPT sí aporta valor real.** El contador **se infla** en 6.538 de 15.780 intercalaciones (resultado final: `count=3` con 2 administradores) si la precondición se evalúa **fuera** de la transacción (variante D0: por ejemplo, un servicio que valida antes de llamar a `executeWithRetry`).
- Con la escritura condicional de ChatGPT sobre `Account`, ese mismo error de implementación da **0 contraejemplos**.
- **Conclusión**: adoptar la escritura condicional, **justificada como defensa en profundidad** contra un error de implementación. No porque el diseño original estuviera roto.

**Confirmado — justificación del singleton (S1a).** Sin el documento dedicado, dos `REVOKE` concurrentes dejan **cero administradores** en 250 de 252 intercalaciones. Con el documento dedicado: 0 contraejemplos. El *write skew* que motiva `PlatformAuthorityState` es real.

**Fuente — matiz nuevo del manual**: solo una escritura que *modifica* el documento adquiere el bloqueo. Por eso el contador debe cambiar **siempre** en un `GRANT`/`REVOKE` exitoso. Un "no cambiar el contador" nunca debe contar como escritura de protección.

`version`: diagnóstico, no control (coherente con ADR-026, que excluye optimistic locking por versión en `identity`).

### #10 — Singleton `_id = "platform-authority"`
Sin disputa. Es necesario para la guarda de #5 y la serialización de #9.

### #11 — Reintentos y commit ambiguo
**Fuente — confirmado en spring-data-mongodb 4.4.4:**
- `MongoTransactionManager.doCommit` **ignora las etiquetas de error** por defecto.
- Cualquier fallo del commit sale como `TransactionSystemException`, con la `MongoException` como causa.
- El propio javadoc ofrece el gancho para **reintentar solo el commit** ante `UnknownTransactionCommitResult`.

**Fuente — confirmado en el driver 5.2.1:**
- El commit es una escritura reintentable: el driver ya lo reintenta una vez.
- Añade `UnknownTransactionCommitResult` ante red, *timeout*, cambio de primario o `RetryableWriteError`.
- Tras el fallo deja la sesión en `COMMITTED`, así que volver a llamar a `commitTransaction()` está soportado (`alreadyCommitted=true`).

**Conclusión**: la precisión de ChatGPT es correcta y técnicamente implementable.
- Ante `UnknownTransactionCommitResult` se **reintenta solo el commit**, lo cual es seguro, nunca el comando de negocio.
- Si sigue indeterminado, se lanza `PlatformAuthorityOutcomeUnknownException`.
- `MongoTransactionRetryHelper` ya recorre la cadena de causas buscando la etiqueta, por lo que detecta el `TransientTransactionError` aunque venga envuelto en `TransactionSystemException`.

**Hueco nuevo — ninguna IA lo cubrió**: qué pasa cuando **se agotan los 3 reintentos**.
- En el modelo, con planificación adversaria, ocurre con frecuencia, porque el helper reintenta **sin espera** mientras la otra transacción sigue abierta.
- Hoy la `MongoException` cruda llega al llamador.
- Hay que decidir una excepción nombrada (por ejemplo, `PlatformAuthorityConcurrentModificationException`) y si el helper debe tener *backoff*.
- La frecuencia del modelo no es una tasa real.

### #12 / #13 — Firmas; `RequestOrganizationInformation`
- La "imprecisión" que señaló ChatGPT en #12 **no existe**: `message` estaba en la fila #13 del prompt.
- El límite de 2000 caracteres **no tiene fuente**. ChatGPT tiene razón en que es decisión humana. Pero "sin límite" tampoco es aceptable, porque es una entrada no acotada.

### Hueco A — Desactivar a un Platform Administrator (aportado por ChatGPT)
**Confirmado y agravado. Modelo S4: el `DeactivateAccountService` actual rompe el invariante en el 100 % de las intercalaciones (210/210)**:
- Resultado: queda `count=1` con el único administrador `INACTIVE`, y nadie puede administrar la plataforma.
- Ni siquiera hace falta concurrencia: basta con ejecutar `REVOKE(A)` y luego `DEACTIVATE(B)`.
- Con la guarda propuesta (`DeactivateAccount` rechaza si `platformAuthority != null`): 0 contraejemplos.
- **Es una enmienda al ADR-026** (comando ya implementado) y debe declararse como tal.

### Hueco B (NUEVO) — `GRANT` sobre una cuenta `INACTIVE`
**Las dos propuestas se rompen.**
- **Modelo S5b**: `GRANT` sobre una cuenta ya `INACTIVE` → administrador inactivo contado en el contador. Entonces un `REVOKE` del último administrador activo supera el filtro `count > 1` y se produce el bloqueo.
- **Modelo S5**: `GRANT(C) || DEACTIVATE(C)` produce lo mismo en 135 de 279 intercalaciones, aun con la guarda del hueco A.
- **Corrección**: `GRANT` exige `status == ACTIVE` (`InactiveAccountException` ya existe). Con esa regla más la guarda del hueco A: 0 contraejemplos.
- Juntas mantienen el invariante **"todo Platform Administrator está ACTIVE"**. Solo con él, `activeAdministratorCount` significa lo que su nombre dice.

---

## 4. Resumen de resultados del verificador

| Escenario | Diseño | Intercalaciones | Resultado |
|---|---|---:|---|
| S1a `REVOKE‖REVOKE` sin singleton | count() sobre accounts | 252 | **Roto**: 250 terminan con 0 administradores |
| S1 `REVOKE‖REVOKE` con singleton | Claude / ChatGPT | 4.326 c/u | 0 contraejemplos, resultados idénticos |
| S2 `GRANT‖GRANT` misma cuenta | precondición fuera de la transacción | 15.780 | **Roto**: 6.538 inflan el contador |
| S2 | fuera de la transacción + filtro condicional ChatGPT | 15.780 | 0 contraejemplos |
| S2 | Claude / ChatGPT (precondición dentro) | 4.326 c/u | 0 contraejemplos, resultados idénticos |
| S3 `GRANT‖REVOKE` misma cuenta | Claude / ChatGPT | 210 c/u | 0 contraejemplos |
| S4 `REVOKE‖DEACTIVATE` | `DeactivateAccount` actual | 210 | **Roto**: 210/210 bloqueo |
| S4 | con guarda | 84 | 0 contraejemplos |
| S5 `GRANT‖DEACTIVATE` misma cuenta | sin exigir ACTIVE | 279 | **Roto**: 135 administrador inactivo |
| S5 | exige ACTIVE + guarda | 279 | 0 contraejemplos |
| S5b `GRANT` a cuenta INACTIVE | sin exigir ACTIVE | 1 | **Roto** |
| S6 `BOOTSTRAP‖BOOTSTRAP` | Claude / ChatGPT | 812 c/u | 0 contraejemplos |
| S7 bootstrap con admin previo y sin estado | Claude / ChatGPT | 1 c/u | **Ambos rotos** |

La salida completa está en `evidencia-adr-038/modelcheck.out`; el verificador es reproducible con `python3 evidencia-adr-038/modelcheck.py` (Python 3, sin dependencias).

---

## 5. Decisiones que necesita el equipo antes de reescribir el ADR-038

1. **H1**: ¿`HumanActor(accountId)` del ADR-035 prevalece (corregir ADR-038 §2.1) o se enmienda el ADR-035?
2. **#1/#2**: ¿excepción determinista o no-op idempotente como `AssignAdministrator`?
3. **#6 + H4/H5**: ¿se aprueba cambiar `AuditLogEntry.actorAccountId` por un actor propio de `identity` (cuenta | sistema)? Toca código de Fase 4 ya implementado.
4. **#11**: excepción nombrada al agotar reintentos, y si se añade *backoff* al helper.
5. **#13**: valor del límite del mensaje, y si se rechaza el excedente (recomendado) o se trunca.
6. **Huecos A y B**: aprobar la guarda en `DeactivateAccount` (enmienda al ADR-026) y la precondición `ACTIVE` en `GRANT`.
7. **#5**: aprobar la precondición global del bootstrap (ninguna cuenta con `platformAuthority`).
8. **H3**: `resolvePrincipal` debe rechazar `INACTIVE` antes de que exista el filtro JWT.
9. **H6**: ¿se documenta como deuda o se corrige que 7 servicios no usen el helper de reintentos?

---

## 6. Lo que sigue sin verificar y el test que lo cerrará

Estos tests son la *Definition of Done*; el modelo no los sustituye. Todos contra MongoDB real (Testcontainers, `mongo:6.0` en *replica set*, como `BaseMongoIntegrationTest`) y con salida literal de Surefire de `mvn test -pl identity`:

- S1–S6 reproducidos con `CyclicBarrier`, afirmando `count == cuentas con platformAuthority` y una entrada de auditoría por transición efectiva. Con aserciones negativas en cada rama excluyente.
- El conflicto de escritura real (código y etiqueta) que devuelve MongoDB 6.0 al escribir el singleton desde dos transacciones.
- `TransientTransactionError` y `UnknownTransactionCommitResult` inyectados con el *failpoint* `failCommand` (`enableTestCommands=1`). Comprobar el reintento del helper, que el comando de negocio **no** se re-ejecuta, y el efecto del gancho `doCommit`.
- `resolvePrincipal` con cuenta `INACTIVE` → `InactiveAccountException`.
- Bootstrap: primera ejecución, segunda ejecución, dos concurrentes, cuenta inexistente, cuenta `INACTIVE`, cuenta ya administradora, y otra cuenta ya administradora sin estado.
