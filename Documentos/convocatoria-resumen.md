# Convocatoria — Resumen de diseño conceptual (no congelado)

**Función actual (desde la consolidación documental del 2026-10-03):** documento de entrada del bloque Convocatoria. Reúne:
- **§0:** índice, mapa y estado documental, que dice dónde está la fuente canónica de cada decisión;
- **§1–§5:** diseño conceptual de la ronda del 18 sept (histórico);
- **§6:** registro cronológico de decisiones humanas.

Esta función la fijó la instrucción humana de la tarea de consolidación del 2026-10-03 (`auditoria-ejecucion-consolidacion-convocatoria.md`). Las dos líneas siguientes son la cabecera original. Describen §1–§5 y se conservan sin cambios. Lo que dice el encabezado de §6 sobre el alcance normativo de las decisiones humanas sigue vigente (CD-04, §0.5).

**Estado:** Diseño pre-ADR, sujeto a extensión. Esta capa se puede seguir ampliando — este documento no es una decisión final, es un punto de continuidad para retomar el trabajo sin perder lo ya acordado.
**Para qué sirve:** que cualquiera (tú, el equipo, otra sesión) pueda ver de un vistazo qué quedó decidido sobre Convocatoria y qué sigue pendiente, antes de pasar a diseñar la siguiente capa de Fase 6.

---

## 0. Índice y mapa documental del bloque Convocatoria (consolidación del 2026-10-03)

Esta sección no introduce ninguna decisión. Ordena las fuentes que ya existen. Ningún documento se borró, movió ni renombró.
- Base: `auditoria-inventario-documental-convocatoria.md` (inventario) y `auditoria-plan-consolidacion-documental-convocatoria.md` (plan).
- Ejecución: `auditoria-ejecucion-consolidacion-convocatoria.md`.

### 0.1 Por dónde empezar

1. **Esta sección §0**, para saber qué documento manda en cada tema.
2. **`ADR-037-convocatoria-ledger-assignment-donationintent.md`**, la normativa consolidada: ADR-037 original con la Enmienda 1 (APROBADA) integrada. La Enmienda 2 (BORRADOR) solo aparece señalada como no normativa (ADR-037 §9).
3. **Dependencia externa — `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md`**: mecanismo de recuperación (estado declarado: Propuesto). **Documento de otro bloque**, no de Convocatoria (§6.21). Se cita solo como referencia.
4. **`implementation_plan.md`**: plan del primer corte, contrato interno de aplicación y modelo físico MongoDB (§17).
5. **`estado-fase6.md`**, solo la fila 1 de §2, §3bis y la primera viñeta de §5: estado de ejecución de Convocatoria.
6. **`handoff-convocatoria.md`**: entrega a otra persona. Contiene el estado, los tests, las colecciones e índices, el contrato interno, el estado del contrato HTTP por operación (sin cerrarlo) y las dependencias. No es normativo.

### 0.2 Mapa documental

| Documento | Función | Estado declarado por el propio documento | Autoridad |
|---|---|---|---|
| `ADR-037-convocatoria-ledger-assignment-donationintent.md` | Normativa consolidada | "Aprobado con Enmienda 1" | **Canónica** |
| `ADR-037-enmienda-1-convocatoria.md` | Fuente original de la Enmienda 1 (integrada en ADR-037) | APROBADA (2026-09-30) | Fuente histórica intacta; ADR-037 es la lectura consolidada |
| `ADR-037-enmienda-2-convocatoria.md` | Fuente original de la Enmienda 2 (**no** integrada) | BORRADOR (2026-10-02) | No normativa hasta su aprobación (§9 del propio documento) |
| `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md` | **Dependencia externa** (documento de otro bloque, §6.21): recuperación automática de la aplicación de fondos | Propuesto | Externo; no forma parte de la normativa de Convocatoria |
| `convocatoria-resumen.md` (este documento) | Índice (§0); diseño conceptual histórico (§1–§5); registro de decisiones humanas (§6) | Ver la cabecera | §6: registro de decisiones con el alcance del encabezado de §6 |
| `implementation_plan.md` | Plan del primer corte; contrato interno y modelo físico (§17) | "APROBADO (revisión 2)"; revisiones 2.1–3 posteriores | Plan de ejecución (regla 3.4) |
| `handoff-convocatoria.md` | Entrega del bloque (resumen operativo para otra persona) | 2026-10-03 | Derivado, no normativo: remite a ADR-037, el plan §17 y este índice |
| `estado-fase6.md` (solo Convocatoria) | Estado operativo | Snapshot de ejecución | Operativa (regla 2.4) |
| `fase-6-estructura-y-perimetro-convocatoria.md` §3 | Perímetro inicial (18 sept) | "Documento de congelación" | **Histórico**: §3.4 superado por ADR-037 §2.3; §3.2 y §3.5 subordinados por N11 (ADR-037 §0). §1–§2 y §4–§5 son de toda Fase 6 |
| `auditoria-c01-idempotencia-fondos.md` | Evidencia: bug C-01 original | Informe temporal (2026-10-02) | Evidencia histórica intacta |
| `auditoria-c01-postcorreccion.md` | Evidencia: C-01 tras la corrección; origen de §6.17 | Informe temporal (2026-10-02) | Evidencia histórica intacta |
| `auditoria-cierre-f1-f2.md` | Evidencia: F-1/F-2 | Informe temporal (2026-10-02) | Evidencia histórica intacta |
| `auditoria-flujo-confirmed-fondos-v3.md` | Evidencia: flujo `CONFIRMED` → fondos | Informe temporal (2026-10-02) | Evidencia histórica intacta |
| `debate-cierre-confirmed-fondos.md` | Historia: opciones previas a D1–D7 | Informe temporal (2026-10-02) | Histórico intacto |
| `auditoria-preimplementacion-cierre-convocatoria.md` | Evidencia: D1–D7, GAP-1 a GAP-4 | Informe temporal (2026-10-02) | Evidencia histórica intacta |
| `auditoria-delimitacion-cierre-convocatoria.md` | Evidencia: frontera del bloque; convenciones de nombre de estado | Informe temporal (2026-10-02) | Evidencia histórica intacta |
| `auditoria-cierre-final-convocatoria.md` | Evidencia: criterios de cierre (dictamen NO CERRADO) | Informe temporal (2026-10-02) | Evidencia intacta (último snapshot de cierre) |
| `auditoria-herencia-fase5-convocatoria.md` | Evidencia: herencia de Fase 5; H-1 a H-3 | Solo lectura (2026-10-03) | Evidencia intacta |
| `auditoria-documental-convocatoria.md` | Evidencia: catálogo, contrato de aplicación y modelo Mongo reales | Solo lectura (2026-10-03) | Evidencia intacta; su contenido técnico se **copió** a `implementation_plan.md` §17 |
| `auditoria-porcentaje-convocatoria-fase6.md` | Evidencia: porcentajes de avance (dictamen "CERRADO") | Sin fecha ni naturaleza declaradas | Evidencia intacta; dictamen opuesto al de `auditoria-cierre-final-…` (CD-14) |
| `auditoria-inventario-documental-convocatoria.md` | Inventario documental | 2026-10-03 | Evidencia intacta |
| `auditoria-plan-consolidacion-documental-convocatoria.md` | Plan de consolidación | 2026-10-03 | Evidencia intacta |
| `auditoria-ejecucion-consolidacion-convocatoria.md` | Informe de esta consolidación | 2026-10-03 | Evidencia |

**Documentos de otros bloques que citan o derivan decisiones de Convocatoria.** Esta consolidación no los modificó (ver §0.6):
- `golden-path.md` (Capa 6);
- `api-contract-matrix.md`, `ADR-041`, `contract-wiring-review.md`, `hallazgos-front-fase2.md`, `plan-api-fase6.md`, `revision-ready-api-fase6.md`, `auditoria-api-convocatoria*.md` (Capa 5);
- `ADR-038`, `identity-resumen.md` (Identidad);
- `ADR-040`, `ia-resumen.md` (IA);
- `ADR-029`, `ADR-032` (`core`);
- `plan-correccion-fase5-e-ia.md`, `estado-fase5.md` (Fase 5).

### 0.3 Dónde está cada decisión

| Tema | Fuente canónica | Estado | Otros documentos que la repiten (no mandan) |
|---|---|---|---|
| Modelo de `Convocatoria` (estado, `publicCode`, visibilidad, configuración versionada, tipos y medios, `onTargetReached`) | ADR-037 §2.1 | Aprobado | §2, §6.3–§6.8 de este documento; `implementation_plan.md` §3.1 |
| Datos mínimos (R1) | §6.15 de este documento | Decisión humana del primer corte | `implementation_plan.md` §3.1 |
| Responsables (`actingRole`, dos operaciones, autoasignación del administrador, contador) | ADR-037 §2.4, §2.5, §5 | Aprobado | §6.2, §6.7, §6.12; plan §3.2–§3.3, §6 |
| Cierre manual | ADR-037 §2.1, §5 | Aprobado | §6.14; plan §10 |
| Campos y precondiciones de `DonationIntent` | ADR-037 §2.6 | Aprobado | §6.4, §6.11; plan §3.5, §9 |
| Estados de `DonationIntent` | ADR-037 §2.6 (`PENDING/CONFIRMED/FAILED/EXPIRED-UNKNOWN`) | Aprobado. `FUNDING_REJECTED`: decisión humana D3 (§6.20), con la enmienda en BORRADOR | Plan §4.4; transiciones implementadas: plan §17.4; `estado-fase6.md` §3bis |
| Semántica de `CONFIRMED` y barrera financiera | ADR-037 §2.6 (E1 §5.3: la transición es la barrera) | **En conflicto (CD-03):** las decisiones humanas F-1, F-2 y D1 (§6.20) dicen otra cosa y la Enmienda 2 está en BORRADOR | §6.17–§6.20; plan §7.2, §8 |
| Confirmación manual | ADR-037 §2.6 (E1 §5.2, N9) | **En conflicto (CD-03):** la decisión humana D5 (§6.20) y la Enmienda 2 §3.1 (BORRADOR) sustituyen la frase de N9 | Plan §9.2 |
| Autorización | ADR-037 §5 | Aprobado | Plan §6 |
| Respaldo del `REPRESENTATIVE` | ADR-037 §5 (E1 §4.4): **pendiente P6** (ADR-032) | Pendiente | §2, §4 de este documento (frases anteriores, ver notas); `fase-6-…` §3.5 |
| Pasarela / webhook | ADR-037 §2.6 pasos 1–2 | Aprobado; integración pendiente (P3) | §6.12; plan §1.3 |
| D1 (el cierre no invalida intenciones) | ADR-037 §2.6bis | Aprobado | §6.14; plan §10 |
| `CampaignFundingLedger` (existe solo con `MONETARY`, `REJECT_EXCESS` completo) | ADR-037 §2.2 | Aprobado | §6.5, §6.11, §6.14; plan §3.4, §8 |
| Transacción `STRICT` y requisito de reintento | ADR-037 §2.3 | Aprobado; integración pendiente | §2, §5, §6.9.5, §6.15; plan §8 |
| `APPLY_FUNDS` | §6.20 D1 (decisión humana) → Enmienda 2 §3.3 | **BORRADOR** (enmienda no aprobada) | Plan §4.2, §8; `estado-fase6.md` §3bis |
| Idempotencia de los comandos de cliente (N12) | ADR-037 §2.7 | Aprobado | §6.13; plan §7.1 |
| Idempotencia de la creación de intención (ID) | §6.15 de este documento | Decisión humana del primer corte | Plan §7.3 |
| `FUNDING_REJECTED` | §6.20 D3 → Enmienda 2 §4 | **BORRADOR** | Plan §4.4, §8 |
| Recuperación (disparo, scheduler, consulta, P9) | De Convocatoria: decisión humana D2 y P9 (§6.20); consulta de recuperables implementada (`implementation_plan.md` §17.5). **Externo:** el mecanismo (disparo inmediato, scheduler) se describe en ADR-043, de otro bloque (§6.21) | D2 y P9: decisiones humanas; Enmienda 2 §5: BORRADOR; ADR-043: Propuesto (externo) | Enmienda 2 §5; plan §8, §11 |
| Dinero no aceptable (P1) | ADR-037 §2.2, §7.2 | Aprobado como regla; mecanismo pendiente | §6.4, §6.11; plan §14.1 |
| Pendientes P1–P7 | ADR-037 §7.2 | Pendientes | Plan §14.1 |
| Pendientes P8–P10 | Enmienda 2 §7 | BORRADOR | — |
| R3, R4 | §6.15 de este documento; plan §14.2 | Fuera del corte / pendiente | — |
| B-1 (dirección `app → convocatoria`) | §6.16 de este documento | Aclaración técnica | Plan §11 |
| Contrato interno de aplicación (comandos, resultados, excepciones) | `implementation_plan.md` §17.1 (operaciones), §17.4 (transiciones), §17.5 (consultas, incluida la de recuperación) | Descriptivo (código del primer corte); pendiente de revisión humana (§16, criterio 7) | `auditoria-documental-convocatoria.md` Parte 3 (origen) |
| Modelo físico MongoDB | `implementation_plan.md` §17.2 (colecciones, campos e índices reales); índices normativos en ADR-037 §2.4, §2.6 | Descriptivo | `auditoria-documental-convocatoria.md` Parte 5 (origen); plan §4.2 (nombres provisionales) |
| Contrato HTTP de Convocatoria | **Otro bloque** (Capa 5: ADR-041, `api-contract-matrix.md`) | Pendiente (P4 y otros); ninguna operación tiene request, response y errores cerrados | Estado por operación, sin cerrarlo: `handoff-convocatoria.md` §8 |
| Dependencias externas | ADR-037 §7.3; plan §11; resumen para entrega en `handoff-convocatoria.md` §10 | — | Enmienda 2 §6 (incluye ADR-043, documento externo, §6.21); `estado-fase6.md` §3bis |
| Golden Path | **Otro bloque** (`golden-path.md`, Capa 6) | — | — |
| G1–G3 (2026-10-01) | Solo `estado-fase6.md` §3bis | **Pendiente de confirmación humana** (§0.4) | — |
| Numeración de los ADR de Fase 6 | §6.1 de este documento (fuente citada inexistente) | **Pendiente de decisión humana** (§0.4) | ADR-037 (nota de numeración) |

### 0.4 Decisiones humanas pendientes para cerrar el orden documental

Ninguna se resolvió en la consolidación. Cada una se registrará en una sección §6.x de este documento cuando se tome.

| ID | Decisión pendiente | Documentos afectados | Opciones encontradas | Por qué no se resuelve automáticamente |
|---|---|---|---|---|
| DH-C-1 | ¿Se aprueba el texto de la Enmienda 2? Su lista de aprobación (§9) incluye además la aprobación de ADR-043, que corresponde a otro bloque (§6.21) | ADR-037 §2.6, §2.7, §9; Enmienda 2; plan rev. 3. ADR-043 solo como dependencia externa | Aprobar (y entonces integrar en ADR-037 como [E2]) / modificar / no aprobar | Las dos exigen aprobación humana explícita (Enmienda 2 §9; ADR-043 "Propuesto"; regla 3.5) |
| DH-C-3 | ¿Se ratifica el catálogo 037–043? ¿Dónde se registra la decisión? | Título de ADR-037; §6.1; Enmienda 1 `:5-7` | Ratificar y registrar en §6.x / otra numeración | La fuente citada (`ADR-042-frontend-web-paxfide-web.md`) no existe; otros bloques dicen "pendiente" |
| DH-C-4 | ¿G1, G2 y G3 fueron decisiones humanas? | `estado-fase6.md:68`; este documento | Registrarlas en §6.x / corregir su calificación | Solo constan en un documento de estado; G3 no figura en la Enmienda 1 |
| DH-C-5 | ¿La revisión 3 de `implementation_plan.md` está aprobada? | `implementation_plan.md` cabecera | Aprobada / pendiente | La cabecera solo declara aprobada la revisión 2 |
| DH-C-7 | ¿Los cambios sin commit en `golden-path.md` y `api-contract-matrix.md` (flujo de la Enmienda 2) se conservan, o los revisan los dueños de esos bloques? | `golden-path.md`, `api-contract-matrix.md` | Ver §0.6 | Son documentos de otros bloques |
| DH-C-8 | ¿Grafía `EXPIRED-UNKNOWN` o `EXPIRED_UNKNOWN`? | ADR-037 §2.6; plan §3.5; documentos de API | Una u otra | La documentación y el enum del código difieren |
| DH-C-10 | ¿Se versionan en Git los documentos de Convocatoria sin seguimiento? | Enmiendas, plan, handoff, auditorías (ADR-043, también sin seguimiento, es de otro bloque) | Commit en rama `chore/` / esperar | Hoy no existe copia histórica de ellos en Git |

DH-C-2 (modalidad de consolidación de ADR-037), DH-C-6 (destino del contrato interno y del modelo físico) y DH-C-9 (función de este documento) quedaron resueltas por la instrucción humana de la tarea de consolidación del 2026-10-03:
- consolidar en ADR-037;
- usar `implementation_plan.md` para contratos internos y modelo físico;
- usar este documento como índice.

### 0.5 Contradicciones documentales abiertas

Numeración de `auditoria-inventario-documental-convocatoria.md` §D. No se resolvió ninguna; las que afectan a ADR-037 están señaladas allí en su lugar.

| ID | Resumen | Dónde está señalada |
|---|---|---|
| CD-02 | Numeración: "aprobada" con fuente inexistente, frente a "tentativa" y "pendiente" | ADR-037 (nota de numeración); §6.1 |
| CD-03 | Barrera y semántica de `CONFIRMED`: E1 §5.3 y N9 (aprobadas) frente a F-1, F-2, D1 y D5 (decisiones humanas, §6.20) y la Enmienda 2 (BORRADOR). El código sigue lo segundo | ADR-037 §2.6, §9 |
| CD-04 | Papel normativo de este documento (cabecera original "pre-ADR" frente al registro de decisiones de §6) | Cabecera de este documento |
| CD-05 | Respaldo del `REPRESENTATIVE` presentado como cerrado (§2, §4; `fase-6-…` §3.5) frente a P6 y ADR-032 | Notas en §2 y §4 de este documento; `fase-6-…` §3.5 |
| CD-06 | `ConvocatoriaReadPort` "ya existente" y `scanBasePackages`: ADR-037 frente al plan §2 | ADR-037 §2.3, §2.6 |
| CD-07 | Plan: aprobación de la rev. 3; base normativa | `implementation_plan.md` (nota de consolidación) |
| CD-08 | `estado-fase6.md` §1 y §5 desactualizados para Convocatoria | `estado-fase6.md` (notas) |
| CD-10 | G1–G3 solo en un documento de estado | §0.4 (DH-C-4) |
| CD-13 | Grafía `EXPIRED-UNKNOWN` / `EXPIRED_UNKNOWN` | ADR-037 §2.6 |
| CD-14 | Dictámenes de cierre opuestos entre dos auditorías | §0.2 |
| CD-09, CD-12 | Discrepancias en documentos de otros bloques | §0.6 |

CD-01 (cabecera de ADR-037 desactualizada) y CD-11 (`fase-6-…` sin marca de superación) quedaron **resueltas** por la consolidación.

### 0.6 Referencias externas que requieren trabajo de otros bloques

No se ejecutaron. El detalle está en `auditoria-ejecucion-consolidacion-convocatoria.md` §7. En resumen:
- **`api-contract-matrix.md` (API):** "módulo sin código"; una sola operación de asignación (P4); `donation-intents` "sin operación de dominio"; respaldo del `REPRESENTATIVE`; webhook "implementado"; fila del webhook y lista de estados con la Enmienda 2 sin commit (CD-09, DH-C-7).
- **`golden-path.md` (Golden Path):** paso 3 reescrito con la Enmienda 2 sin commit; §3 cita el respaldo del `REPRESENTATIVE` como existente (CD-05, DH-C-7).
- **`ADR-040`, `ADR-041`:** numeración antigua "ADR-037/034/035/036" (CD-12).
- **`hallazgos-front-fase2.md`:** usa "ADR-037" para la API y "ADR-033" para Convocatoria.
- **`plan-correccion-fase5-e-ia.md`, `estado-fase5.md`, `ADR-042`:** describen la renumeración como pendiente o en `develop` local.
- **`ADR-032`:** redacción del respaldo del `REPRESENTATIVE` y de P6.

---

## 1. Qué es Convocatoria

Una convocatoria es una campaña de recaudación que una `Organization` crea y administra. Las personas se "vinculan" a ella donando dinero — con o sin cuenta. Las donaciones en especie siguen siendo físicas (ya resuelto desde antes, sin cambios). *(Nota 30 sept: la verificación de código de §6.9 muestra que ningún activo físico conoce hoy su convocatoria; §6.10 decide cómo se corrige.)*

## 2. Lo que quedó decidido

**Identidad y acceso**
- `publicCode` (acceso a la convocatoria, antes de donar) ≠ `trackingCode` (seguimiento de una donación, después de donar).
- Donante con o sin cuenta — ambos caminos válidos.
- **Descubrimiento público confirmado**: existe un panel donde se listan todas las convocatorias públicas y abiertas, accesible a cualquier usuario. Dos casos de uso distintos:
  - **Descubrimiento** → panel público; solo muestra convocatorias con visibilidad pública **y** estado abierto.
  - **Acceso directo** → `publicCode`/link/QR/otro medio; funciona igual para públicas y privadas-por-enlace (una privada-por-enlace nunca aparece en el panel, pero su link/código sigue resolviéndola).
- El panel no necesita una proyección nueva: al ser `Convocatoria` CRUD, es una consulta filtrada sobre su propio estado (`visibility`, `status`, fechas) — mismo mecanismo que el resto de la vista pública.

**Responsables**
- Nunca puede haber 0 responsables activos en una convocatoria — retirar al último sin reemplazo simultáneo se rechaza (mismo mecanismo que ya protege al `REPRESENTATIVE` único de una `Organization`).
- Responsable puede ser `EMPLOYEE`, o `REPRESENTATIVE` como respaldo cuando no hay `EMPLOYEE` asignado — sin que esto lo convierta en `EMPLOYEE` ni le dé todos sus permisos. *(Ampliado el 30 sept: un `ADMINISTRATOR` también puede ser responsable — ver §6.7 y §6.12. Verificado el 30 sept: el respaldo del `REPRESENTATIVE` está decidido pero **no existe en el código** — ver §6.9.2. La condición exacta de activación del respaldo ahora que un `ADMINISTRATOR` puede ser responsable es el pendiente P6 de la enmienda; esta frase no lo resuelve.)*
- Respaldo del `REPRESENTATIVE` limitado exactamente a: `REGISTER_PHYSICAL_ASSET` y `SPLIT_PHYSICAL_ASSET`. Los comandos de `Fund` quedan siempre fuera, y `REGISTER_PHYSICAL_ASSET_FROM_DONATION` también (semántica financiera distinta). *(Nota 30 sept: `REGISTER_PHYSICAL_ASSET_FROM_DONATION` ya está implementado y autorizado para `EMPLOYEE`, según §6.9.)*

**Asignación empleado↔convocatoria**
- La asigna `ADMINISTRATOR`. No hay autoasignación. Es retirable. Tiene estado y fechas. *(Precisado el 30 sept: un `ADMINISTRATOR` sí puede autoasignarse como responsable, auditado — ver §6.7; asignar un empleado y designar un administrador son operaciones separadas — ver §6.12.)*
- Un empleado solo puede estar asignado a **una** convocatoria activa a la vez (se descartó la participación simultánea en varias — poca necesidad real, complejidad de horarios innecesaria para el MVP).

**Dinero y meta**
- `Fund` es **por donante**, no por convocatoria — una convocatoria tiene muchos `Fund`, uno por cada persona que dona. Esto fue una corrección importante a mitad del diseño.
- `targetAmount` + `targetPolicy`, con tres opciones (no dos): `FLEXIBLE` (se puede superar), `STRICT` (no debe superarse), `CLOSE_ON_TARGET` (al llegar a la meta, la organización elige cerrar, rechazar el excedente, o aceptarlo).
- Meta y política quedan fijas una vez que la convocatoria empieza a recibir dinero — cambiarlas después requiere una operación explícita, no una edición silenciosa.
- **`STRICT` se resuelve mediante una transacción MongoDB compartida real** (decisión revisada — ver nota de corrección al final del documento): `CampaignFundingLedger`, el `FUNDS_CLEARED` de `Fund` y el mensaje de Outbox correspondiente participan en la misma unidad transaccional MongoDB, reutilizando el `MongoTransactionManager` canónico ya provisto por `app` y el patrón `TransactionalEventPublisher`/`@Transactional` ya probado para Event Store + Outbox (Testcontainers real). La coordinación ocurre en un Application Service orquestador en `app`; `convocatoria` mantiene `contracts` como única dependencia directa, sin importar `core`. Los mecanismos de retry permanecen separados y reutilizados: `CommandRetryTemplate` para conflictos de concurrencia de dominio, retry acotado de `TransientTransactionError` para fallos transitorios de Mongo — ningún mecanismo nuevo. El alcance de la transacción se limita a escrituras MongoDB (nunca HTTP, blockchain o IA dentro de ella). *(Estado 30 sept: diseño aprobado, integración pendiente — el orquestador no existe todavía; ver §6.9.5.)*

**Visibilidad**
- Pública, o privada-por-enlace (no aparece en listado/búsqueda, pero el link/QR sigue funcionando igual). Control de acceso real con invitación queda fuera del MVP.
- Disponible 24/7 dentro del rango de fechas de la convocatoria.
- **`visibility` (pública/privada), `status` (abierta/cerrada) y ventana temporal (fechas) son tres cosas distintas, no una sola.** "Dentro del rango de fechas" no equivale automáticamente a "abierta": si la política es `CLOSE_ON_TARGET`, alcanzar la meta puede cerrar la convocatoria antes de su fecha de fin. Solo entra al panel público lo que cumple **las tres** condiciones a la vez. La definición exacta de `status` (campo propio vs. derivado de fechas+meta) queda para el review formal, no se fija aquí. *(Nota 30 sept: ADR-037 §2.1 fijó `status` como campo persistido; §6.14 decide el cierre manual y que no hay cierre automático por fecha; el efecto de la ventana de fechas sobre la creación de intenciones es el pendiente P7.)*

**Cómo se construye técnicamente**
- `Convocatoria` y `CampaignFundingLedger` son CRUD + audit log (como `identity`), no event-sourced — sus reglas son todas de estado actual, no necesitan reconstrucción histórica.
- Viven en un módulo nuevo (`convocatoria`) que depende únicamente de `contracts` — reutiliza lo que ya existe para roles/organización (`IdentityPrincipalPort`), sin importar tipos de `identity` ni de `core`.
- La vista pública (meta, recaudado, estado) se lee directamente de estos dos componentes — no hace falta una proyección nueva; los cuatro read models existentes (`DonationProjection`, etc.) resuelven un problema distinto (por donación individual, no por convocatoria).
- Coordinación con `Fund` vía orquestación externa (mismo patrón ya usado para heredar `organizationRef` de `Fund` a `PhysicalAsset`), no dependencia directa entre módulos.

## 3. Lo que falta / sigue abierto

Con el descubrimiento público confirmado, esto es lo que queda genuinamente pendiente:

- **Definición exacta de `status` (abierta/cerrada)**: si es un campo propio o derivado de fechas + `targetPolicy`, y cómo interactúa `CLOSE_ON_TARGET` con la salida del panel público. Material para el review, no decidido aquí a propósito.
- **Riesgo a llevar al review**: el panel público (`GET /campaigns` o equivalente) y cualquier endpoint de listado necesitan paginación/límite obligatorio — un listado público sin tope es superficie de abuso (mismo riesgo que ya se identificó para otros endpoints públicos del sistema).
- **Redacción formal de la enmienda a ADR-032** (contenido ya decidido — respaldo de `REPRESENTATIVE` — falta el documento normativo).
  *(Nota de la consolidación del 2026-10-03: el respaldo del `REPRESENTATIVE` no existe en el código y su activación es el pendiente P6 (ADR-037 §5, §7.2). ADR-032:53 excluye al `REPRESENTATIVE`. Esta frase no describe una regla vigente — CD-05.)*
- **Review formal de Modo de Arquitectura (12 puntos)** de `Convocatoria` + `CampaignFundingLedger` — todo lo anterior es consenso conceptual, no ha pasado por responsabilidad, contratos, errores, concurrencia, persistencia, testing y riesgos.
- **Golden Path de la demo** — transversal a toda Fase 6, no específico de esta capa.

## 4. Cierre de esta ronda

**Convocatoria queda cerrada como diseño conceptual — no como diseño arquitectónico ejecutable.** Distinción explícita, para que nadie lea "cerrado" como "listo para implementar":

```text
Diseño conceptual             CERRADO
Perímetro funcional           CERRADO
Casos de uso públicos         CERRADO
Frontera modular              CERRADA
Naturaleza CRUD               CERRADA
Autorización de respaldo      CERRADA
API concreta                  PENDIENTE (review formal)
Persistencia detallada        PENDIENTE (review formal)
Concurrencia                  PENDIENTE (review formal)
Testing                       PENDIENTE (review formal)
Enmienda ADR-032              PENDIENTE de redacción
Review arquitectónico 12 pts  PENDIENTE
```

*(Nota de la consolidación del 2026-10-03: "Autorización de respaldo CERRADA" es el estado conceptual del 18 sept. El respaldo del `REPRESENTATIVE` sigue pendiente (P6, ADR-037 §5 y §7.2) y no existe en el código — CD-05. La API, la persistencia, la concurrencia y el testing pasaron después por ADR-037, la Enmienda 1 y `implementation_plan.md`.)*

**Restricción de arquitectura a llevar al review** (no se diseña ahora, pero debe quedar como requisito desde el origen del endpoint público de listado): límite máximo de resultados, paginación obligatoria, ordenamiento determinista, filtros permitidos explícitos, tamaño de página no controlable arbitrariamente por el cliente, y respuesta que nunca exponga datos privados.

Con esto, Fase 6 puede avanzar a la siguiente capa conceptual (Identidad/`HumanAccount`, Blockchain, o IA) sin esperar a que el Golden Path de la demo esté definido — el Golden Path condiciona el plan de ejecución final, no el diseño conceptual de cada capa.

Este resumen existe para no tener que reconstruir el hilo completo de la conversación cada vez que se retome.

## 5. Nota de corrección posterior al cierre

Después de cerrar esta ronda, se detectó que la justificación original de "STRICT con tolerancia documentada" se apoyaba en una premisa incompleta: se verificó `MongoConfig.java` de `core` (sin `MongoTransactionManager`) pero no la configuración de `app`, el módulo de ensamblaje. `documento-maestro-proyecto.md`/`technical_documentation.md` confirman que `app` ya provee un `MongoTransactionManager` canónico, probado con un smoke test transaccional real que cubre Event Store + Outbox. Eso invalidó la premisa y llevó a reabrir y resolver `STRICT` como transacción compartida real (§2, "Dinero y meta"). El resto de las decisiones de esta capa no se vieron afectadas. Se deja registrado explícitamente, en vez de editar la historia en silencio — coherente con el principio de trazabilidad que rige todo el proyecto.

---

## 6. Ronda D2 / D3 / D4 / CLOSE_ON_TARGET (29–30 sept 2026) — decisiones de producto del equipo y huecos detectados

**Estado de esta sección:** las tablas de §6.7, §6.10, §6.11, §6.12, §6.13 y §6.14 y las precisiones técnicas de §6.8 son **decisiones humanas aprobadas por el equipo (30 sept 2026)**. Esa aprobación **no** es una enmienda normativa: ADR-032, ADR-037 (Convocatoria), ADR-029 y los demás ADRs afectados siguen vigentes sin cambios hasta que las enmiendas correspondientes se redacten, revisen y aprueben (regla 3.5 de `reglas-equipo-y-agentes.md`). Ninguna rama `feat/` puede abrirse sobre estas piezas antes de eso. §6.9 registra la verificación de código del 30 sept. El resto de §6 registra el razonamiento, las alternativas y los huecos todavía abiertos.

**Estado de cierre de la ronda (30 sept 2026):** D2, D3, D4 (salvo H-D4.3, pendiente explícito) y `CLOSE_ON_TARGET` quedan cerrados como decisiones del equipo. El diff del ADR de Convocatoria se hizo como material de trabajo (no se conserva como documento propio) y se tradujo a `ADR-037-enmienda-1-convocatoria.md`, **APROBADA por decisión humana explícita el 30 sept 2026 con N1–N12** (estado del documento actualizado el 1 oct 2026, ver §6.15). La condición "ninguna rama `feat/` antes de la enmienda" queda cumplida para ADR-037; sigue vigente para ADR-032, ADR-029 y ADR-041.

### 6.1 Nota de vocabulario (obligatoria para leer el resto)

El equipo usa "representante de la convocatoria" para la persona que responde por ella. Ese nombre **colisiona** con el rol `REPRESENTATIVE` de `Organization` (ADR-026, único por organización, con respaldo acotado por ADR-032). En toda la documentación se usará **"responsable de la convocatoria"** para el concepto de campaña, y `REPRESENTATIVE` solo para el rol organizacional. Para tipos de donación se usa `IN_KIND` (término de ADR-029), no `SPECIES`. Para medios de pago se usa `GATEWAY`, `BANK_TRANSFER`, `CASH`.

**Numeración de ADRs (corregido el 30 sept):** la renumeración de Fase 6 quedó **aprobada el 2026-09-28** por decisión humana explícita (tabla en `ADR-042-frontend-web-paxfide-web.md`): Convocatoria 033 → **037**, Identidad 034 → **038**, Blockchain 035 → **039**, IA 036 → **040**, APIs/Frontend 037 → **041**. Una versión anterior de esta nota decía que la numeración "no se resuelve aquí"; era incorrecta. El archivo del ADR de Convocatoria aún se llama `ADR-033-convocatoria-ledger-assignment-donationintent.md` (renombrado pendiente según `plan-correccion-fase5-e-ia.md`). **En §6, las menciones a "ADR-033" como ADR de Convocatoria se leen como ADR-037** (texto histórico, no reescrito); los documentos nuevos usan la numeración vigente.
*(Nota de la consolidación del 2026-10-03: `ADR-042-frontend-web-paxfide-web.md` no existe en `Documentos/`; el archivo del ADR de Convocatoria se llama `ADR-037-…` desde el commit `b417d3a` (2026-09-27). La ratificación de la numeración es una decisión pendiente — §0.4, DH-C-3; CD-02.)*

### 6.2 D2 — ¿Quién puede ser responsable?

**Decisión de producto del equipo:**
- Un `ADMINISTRATOR` puede ser responsable de una convocatoria sin tener también el rol `EMPLOYEE`.
- El registro de responsabilidad conserva, de forma explícita: convocatoria, responsable y **rol con el que actúa** (`actingRole`), para que la organización no pierda el dato de quién la representó.
- Motivo: si el empleado no está disponible, la organización cubrirá con el administrador de todas formas; prohibirlo solo haría que el registro mienta sobre quién respondió.

**Conflictos detectados contra la documentación vigente (a resolver en las enmiendas):**
- **C-D2.1 — Autoridad operativa.** ADR-032 fija `REGISTER_PHYSICAL_ASSET` y `SPLIT_PHYSICAL_ASSET` → `{EMPLOYEE}` y excluyó `ADMINISTRATOR` deliberadamente (`estado-fase5.md`). La decisión de §6.7 le concede esas dos operaciones solo sobre su convocatoria → enmienda a ADR-032.
- **C-D2.2 — Cardinalidad y unicidad.** ADR-033 §2.5 cuenta para "≥1 responsable activo" solo `EMPLOYEE` asignados, y §2.4 protege "un empleado, una convocatoria activa" con índice único parcial. La decisión de §6.7 cambia ambos → enmienda a ADR-033 (el índice pasa a aplicarse solo con `actingRole = EMPLOYEE`).
- **C-D2.3 — Operación inexistente en Identity.** Solo relevante para la Opción A (descartada): no existe operación para añadir `EMPLOYEE` a un miembro existente. Con la opción elegida, `identity` no cambia.
- **C-D2.4 — ¿Dónde vive la comprobación contextual?** ✅ Resuelto (§6.10): puerto nuevo mínimo en `contracts`.

**Opciones consideradas:**
- **Opción A (descartada):** exigir que el administrador también posea `EMPLOYEE`. Requería una operación nueva en `identity` y forzaba dos roles para representar una sola función.
- **Opción B / C (elegida):** administrador responsable sin `EMPLOYEE`, `actingRole = ADMINISTRATOR`. Los roles permanentes (`Membership`) no cambian; la responsabilidad se registra por convocatoria; las capacidades operativas **no se heredan** de la responsabilidad, se autorizan explícitamente por operación. Precedente: el respaldo del `REPRESENTATIVE` (enmienda ADR-032) ya otorga una capacidad contextual acotada sin otorgar otro rol.

**Verificación documental de D2.3 (varias convocatorias):** la regla de "una convocatoria activa" está escrita solo para empleados y por motivo de horarios — `fase-6-estructura-y-perimetro-convocatoria.md` §3.2 ("participación simultánea descartada explícitamente por el equipo el 18 sept 2026, por baja necesidad real y complejidad de colisión de horarios") y §2 de este resumen. Permitir varias al administrador no contradice ningún ADR; la diferencia queda explícita en §6.7.

**Riesgo aceptado:** un administrador podría ser responsable de todas las convocatorias, volviendo opcionales a los empleados.

**Estado:** ✅ cerrado (§6.7, §6.8.1, §6.10, §6.12); pendiente de enmienda a ADR-032 y ADR-037.

### 6.3 D3 — Tipos de donación aceptados

**Decisión de producto del equipo:**
- Cada convocatoria declara qué acepta: solo dinero, solo especie, o ambos (`acceptedDonationTypes`, conjunto no vacío de `{MONETARY, IN_KIND}`), elegido al crearla. Lo decide la organización; la plataforma no es intermediaria.
- Puede modificarse después, pero si ya existen donaciones el cambio **exige solicitud y aprobación** dentro de la propia organización (el administrador no puede cambiarlo por decisión propia). PaxFide nunca aprueba.

**Reglas (las marcadas ✅ aprobadas en §6.7/§6.8; el resto pendiente de aprobación en la enmienda):**
- Antes de la primera donación (sin `DonationIntent`, `Fund` ni activo en especie asociado): edición directa por `ADMINISTRATOR`, auditada.
- ✅ Después de la primera donación: solo vía solicitud de cambio, con `requester` ≠ `approver`.
- ✅ Nunca se invalidan retroactivamente donaciones ya registradas, ni `DonationIntent` creadas antes de la aprobación del cambio (conservan la validez que tenían en su momento de creación).
- ✅ Una convocatoria `CLOSED` no admite cambios de configuración.
- Prohibido dejar el conjunto vacío (para dejar de recibir se cierra la convocatoria).
- ✅ Añadir `MONETARY` a una convocatoria que era solo especie es una sola transición que incluye meta, política y medios de pago (invariante §6.8.4).
- Una convocatoria solo `IN_KIND` no tiene `targetAmount`/`targetPolicy` ni `CampaignFundingLedger`.
- ✅ El registro de especie valida contra la configuración vigente que `IN_KIND` esté aceptado; es regla de negocio, no de autorización (§6.12).

**Huecos:**
- **H-D3.1 — Aprobador.** ✅ Resuelto (§6.7): otro `ADMINISTRATOR` o el `REPRESENTATIVE`, distinto del solicitante; si no existe ninguno, el cambio se bloquea. Base: la propia respuesta del equipo en D3.6 ("el administrador no puede cambiarla por decisión propia") hace de la segunda persona un requisito del dominio, no una recomendación. Como toda organización tiene siempre un `REPRESENTATIVE`, el bloqueo solo afecta a organizaciones donde una misma persona es el único administrador y el representante. Descartadas: autoaprobación "unilateral" y cerrar la convocatoria para crear otra.
- **H-D3.2 — Ningún activo conoce su convocatoria.** Verificado en código (§6.9.1). ✅ Resuelto por decisión del equipo (§6.10): `campaignRef` en `PhysicalAsset`, heredado/recibido según el camino, inmutable.
- **H-D3.3 — "Excepción por donante" (planteada por el equipo): descartada.** Una convocatoria solo-especie que acepta dinero "por excepción" convierte la configuración en sugerencia. **La excepción va sobre la configuración, no sobre la donación**: el mecanismo legítimo es la solicitud de cambio (añadir `MONETARY`), o donar a otra convocatoria de la misma organización que sí acepte dinero.
- **H-D3.4 — Ubicación de la validación de `IN_KIND` en el Camino B.** 🟡 Pendiente explícito (§6.12): el registro en especie ocurre en `core` y la configuración vive en `convocatoria`; la ubicación concreta se decide al diseñar el caso de uso. No se resuelve ampliando el puerto contextual.

**Estado:** ✅ cerrado; 🟡 H-D3.4 pendiente explícito; pendiente de enmienda (ADR-037 y ADR-029).

### 6.4 D4 — Medios de pago monetario

**Decisión de producto del equipo:**
- Una convocatoria puede habilitar varios medios a la vez: pasarela de pago, transferencia bancaria y efectivo (`acceptedPaymentMethods`, conjunto no vacío, obligatorio solo si acepta `MONETARY`).
- La organización decide cuáles habilita (puede ser solo transferencia o solo efectivo); el donante elige entre los habilitados.
- Cada medio necesita su propio mecanismo de confirmación y trazabilidad.

**Mecanismo propuesto por medio (pendiente de aprobación formal en la enmienda):**

| Medio (`paymentMethod`) | Fuente de confirmación (`confirmationSource`) | Quién confirma | Evidencia | Qué garantiza el sistema |
|---|---|---|---|---|
| `GATEWAY` | `PAYMENT_PROVIDER` | El proveedor, vía webhook firmado (ADR-033 §2.6, sin cambios) | `providerEventId`, `paymentSessionId` | Confirmación externa verificable |
| `BANK_TRANSFER` | `ORGANIZATION` | Humano de la organización, tras ver el movimiento en su banco | Referencia bancaria (única, §6.11) | Declaración atribuida a una persona concreta |
| `CASH` | `ORGANIZATION` | Humano de la organización, en persona (génesis directa ya prevista en ADR-016) | Número de recibo | Declaración atribuida; el sistema no puede probar que el dinero existió |

- `paymentMethod` y `confirmationSource` son dos campos distintos, no variantes de un único `CONFIRMED`: así el modelo expresa "transferencia confirmada por la organización" sin fingir que PaxFide verificó el banco.
- Toda confirmación registra quién, cuándo, qué medio y qué referencia/evidencia.
- La confirmación humana **nunca** sustituye al webhook en la pasarela.
- La vista pública y los hechos de auditoría (IA) deben distinguir "confirmado por proveedor" de "declarado por la organización"; presentarlos igual sería una afirmación falsa de verificación. En particular, una confirmación de efectivo **nunca** se presenta como evidencia independiente de que el dinero existió ("X declaró haber recibido", no "PaxFide verificó").
- Confirmar transferencia/efectivo es `CLEAR_FUNDS_AS_GENESIS` → hoy `{ADMINISTRATOR}` (ADR-032). Si se quiere que un `EMPLOYEE` confirme, es enmienda a ADR-032. *(Histórico, sustituido por la Enmienda 2 de ADR-037 §3.1: confirmar no es la génesis; la confirmación manual exige `ADMINISTRATOR` y la génesis pertenece a la aplicación posterior. Ver §6.20.)*

**Huecos:**
- **H-D4.1 — `DonationIntent` asume pasarela.** Necesita `paymentMethod`, y `paymentSessionId` solo aplica a pasarela → enmienda ADR-037 §2.6.
- **H-D4.2 — Vencimiento de transferencias.** ✅ Resuelto (§6.11).
- **H-D4.3 — Dinero recibido que el dominio no puede aceptar.** Agrupa dos casos con la misma raíz: (a) transferencia/efectivo recibido pero rechazado por `STRICT`/`CLOSE_ON_TARGET + REJECT_EXCESS`; (b) transferencia que llega después de que su intención venció.
  - ✅ **Regla aprobada (§6.7, §6.11):** ese dinero **no entra al `Fund` ni al `CampaignFundingLedger`** y **no se convierte en donación aceptada**; queda registrado fuera de ambos como **pendiente de resolución**, con resolución manual trazable. No puede ser un `Fund`, porque un `Fund` pertenece a la convocatoria que rechazó el dinero.
  - Se registra como caso propio (estado, motivo, actor, momento, evidencia), separado de `DonationIntent` y de `Fund`, para no contaminarlos con estados de una excepción operacional.
  - 🟡 **Pendiente explícito de la enmienda (aprobado como tal):** mecanismo definitivo (devolución, conciliación manual u otro). Motivo: devolver exige contactar al donante y, para transferencia, conocer su cuenta bancaria — un donante sin cuenta solo existe como `donorRef` opaco. Decidir la devolución obliga antes a decidir qué información financiera necesita el sistema, quién la proporciona, dónde se almacena y por cuánto tiempo (PII). No se implementa ninguna transición a "devuelto" hasta entonces.
- **H-D4.4 — Doble confirmación.** ✅ Resuelto (§6.11).
- **H-D4.5 — Cambio de medios habilitados.** ✅ Resuelto (§6.11).
- **H-D4.6 — Idempotencia del webhook y del ledger.** ✅ Resuelto (§6.10): barrera `PENDING → CONFIRMED` antes del ledger y `commandId` determinista. *(Histórico: la barrera la sustituye el comando de sistema `APPLY_FUNDS` por intención, Enmienda 2 de ADR-037 §3.3; §6.20.)*
- **H-D4.7 — Integración del webhook y del proveedor.** 🟡 Integración pendiente (§6.12). La correlación por `paymentSessionId` es diseño cerrado; no se reabre.

**Estado:** ✅ cerrado en sus reglas conocidas; 🟡 H-D4.3 y H-D4.7 pendientes explícitos; H-D4.1 se resuelve en la enmienda.

### 6.5 CLOSE_ON_TARGET

**Confirmado por el equipo:** contempla las tres opciones — cerrar al alcanzar la meta, rechazar el excedente, o aceptarlo. Consistente con ADR-037 §2.2: la opción se fija al crear la convocatoria, no se decide en vivo.

**✅ `REJECT_EXCESS` aprobado (§6.11):** un pago no se acepta a medias; la donación es una unidad indivisible. `REJECT_EXCESS` rechaza **completa** la donación que haría superar la meta. Ejemplo: meta 1.000, acumulado 900, donación de 200 → se rechazan los 200 completos. Nombre propuesto: `onTargetReached ∈ {CLOSE, REJECT_EXCESS, ACCEPT_EXCESS}`.

**Estado:** ✅ cerrado.

### 6.6 Orden de trabajo y qué bloquea la implementación

Orden aprobado por el equipo (30 sept 2026, revisado):

1. ✅ Registrar las decisiones de §6.7 y aprobar las precisiones de §6.8.1 y §6.8.3–§6.8.5.
2. ✅ Verificar en código H-D3.2 (§6.9.1) y decidir (§6.10).
3. ✅ Verificar en código C-D2.4 (§6.9.2) y decidir (§6.10). ✅ Verificar la propagación de `appendAndOutbox` (§6.9.5).
4. ✅ Cerrar los huecos restantes de D4 (§6.11); H-D4.3 queda como pendiente explícito de la enmienda.
5. ✅ Diff del ADR de Convocatoria (material de trabajo) y decisiones A/B derivadas (§6.12).
6. **En curso:** `ADR-037-enmienda-1-convocatoria.md` (BORRADOR; incluye la decisión B2 de §6.13, la regla de precedencia y los ajustes y el cierre manual de §6.14) → aprobación humana, incluidas N1–N12. La auditoría de cierre de §6.14 sustituye cualquier otra revisión de diseño previa a la aprobación.
7. `implementation_plan.md` del módulo `convocatoria` → revisión única → aprobación (regla 3.4).
8. Implementación del primer corte de Convocatoria. Las enmiendas de ADR-032, ADR-029 y del contrato HTTP (ADR-041), y la dependencia de `core`/ADR-040 de la enmienda §7.4, avanzan en paralelo y solo bloquean las integraciones que dependen de ellas (gate de implementación del 30 sept: no bloquean el dominio ni los casos de uso de Convocatoria).

Regla del proyecto que se mantiene: **leer → arreglar/tapar/terminar cada ADR → implementar → documentar**. No se implementa mientras quede una decisión normativa pendiente. Límite de alcance acordado: no se abre un nuevo ciclo de mejoras.

**Regla editorial para la enmienda (aprobada):** ningún pendiente se convierte en decisión implícita. La enmienda distingue explícitamente entre decisión aprobada, requisito de diseño e integración pendiente, para que ninguna implementación termine tomando una decisión de producto porque el ADR no fue suficientemente explícito. Todo lo que la enmienda no modifica explícitamente permanece vigente sin cambios respecto de ADR-037 original.

Pendientes explícitos de la enmienda (P1–P7): H-D4.3 (mecanismo del dinero no aceptable, P1); H-D3.4 (ubicación de la validación de `IN_KIND`, P2); H-D4.7 (integración del webhook/proveedor, P3); contrato HTTP de asignación derivado de §6.12 (P4); semántica del efectivo (`CASH`) respecto de `DonationIntent` y de la configuración (P5); condición de activación del respaldo del `REPRESENTATIVE` cuando el único responsable es un `ADMINISTRATOR` (P6, se decide en la enmienda de ADR-032); efecto de la ventana de fechas sobre la creación de intenciones (P7).

Pendientes fuera de esta ronda (registrados en §6.9.4, no se ejecutan ahora): correcciones documentales → `chore/` después de la enmienda; índice del test → `fix/` con test de regresión cuando se apruebe tocar código.

### 6.7 Decisiones aprobadas por el equipo — 30 sept 2026

*Formalizada en la Enmienda 1 de ADR-037 (APROBADA el 2026-09-30) e integrada en ADR-037 por la consolidación del 2026-10-03. El texto de esta sección se conserva como registro de la decisión humana.*

**Aprobación humana explícita del equipo, como decisión de producto. No constituye enmienda normativa de ningún ADR (ver encabezado de §6).**

| Decisión | Resultado aprobado |
|---|---|
| Admin responsable sin `EMPLOYEE` | Sí, con `actingRole = ADMINISTRATOR` |
| Registrar y dividir activos | Sí, solo en la convocatoria donde es responsable (capacidad contextual, no global) |
| Cuenta para "≥1 responsable activo" | Sí |
| Varias convocatorias a la vez | `ADMINISTRATOR`: sí. `EMPLOYEE`: máximo una |
| Autoasignación como responsable | `ADMINISTRATOR`: sí, auditada. `EMPLOYEE`: no |
| Autoaprobación de cambios de configuración | No (autoasignación ≠ autoaprobación) |
| Aprobador de cambios | Otro `ADMINISTRATOR` o el `REPRESENTATIVE`, distinto del solicitante; si no existe ninguno, el cambio se bloquea |
| `DonationIntent` creada antes del cambio | Conserva la validez que tenía al crearse |
| Donaciones pasadas | Nunca se reescriben |
| Convocatoria `CLOSED` | No admite cambios de configuración |
| Dinero que no puede aceptarse | No entra al `Fund` ni al ledger; queda pendiente de resolución con salida registrada; mecanismo definitivo deliberadamente pendiente |

### 6.8 Precisiones técnicas — aprobadas por el equipo el 30 sept 2026

*Formalizada en la Enmienda 1 de ADR-037 (APROBADA el 2026-09-30) e integrada en ADR-037 por la consolidación del 2026-10-03. El texto de esta sección se conserva como registro de la decisión humana.*

Surgen de una revisión externa del 30 sept, evaluada y ajustada. **No cambian ninguna decisión de §6.7**; fijan la semántica con la que esas decisiones deben pasar a las enmiendas.

**6.8.1 ✅ Autorización contextual como conjunto cerrado.** La enmienda a ADR-032 debe enumerar explícitamente los comandos que gana el administrador responsable (hoy `REGISTER_PHYSICAL_ASSET` y `SPLIT_PHYSICAL_ASSET`), igual que `REPRESENTATIVE_BACKUP_COMMANDS`, nunca "los permisos de `EMPLOYEE`". Un comando nuevo exclusivo de `EMPLOYEE` no se hereda sin decisión explícita. La autorización evalúa operación + rol permanente + relación contextual con la convocatoria + recurso; ser responsable de una o varias convocatorias no altera la autoridad administrativa global del `ADMINISTRATOR`.

**6.8.2 ✅ C-D2.4 — ¿Dónde vive la comprobación contextual?** `RoleAuthorizationPolicy.authorize(principal, commandType)` no recibe la convocatoria, y `CampaignAssignment` vive en `convocatoria`, que `core` no puede importar. Verificado en §6.9.2; resuelto en §6.10 (puerto nuevo mínimo en `contracts`).

**6.8.3 ✅ Configuración versionada con efecto prospectivo.** `Convocatoria` lleva una versión de configuración. Los cambios aprobados producen una versión nueva y rigen solo hacia adelante. `DonationIntent` guarda la versión de configuración contra la que se validó al crearse (no una copia completa). Por D1, el webhook no revalida configuración: esa versión es **prueba** de bajo qué reglas nació la intención, no una segunda validación.

**6.8.4 ✅ Invariante de consistencia de configuración.** Invariante de `Convocatoria`, comprobado en todo estado (también en la creación, no solo en el flujo de cambio): si `MONETARY ∈ acceptedDonationTypes`, entonces `acceptedPaymentMethods` no está vacío y `targetAmount`/`targetPolicy` están definidos. Añadir `MONETARY` es una sola transición de versión; nunca existe, ni por un instante lógico, una configuración monetaria incompleta.

**6.8.5 ✅ Concurrencia de configuración.** Dos carreras distintas, con tratamiento distinto:
- **Cambio contra cambio:** escritura condicional sobre la versión esperada; si la versión ya cambió → conflicto, nunca sobrescritura silenciosa. La solicitud de cambio guarda la versión sobre la que se pidió; si al aprobarla la configuración ya avanzó, la aprobación falla (se estaría aprobando algo pensado para otra configuración).
- **Cambio contra creación de `DonationIntent`:** sin conflicto. La intención se valida contra la versión que leyó y la registra (§6.8.3); si el cambio se confirma un instante después, la regla aprobada ("conserva la validez que tenía al crearse") ya resuelve el caso. Bloquear aquí añadiría contención sin proteger ningún invariante.

### 6.9 Verificación de código — 30 sept 2026

**Fuente y alcance de la evidencia:** informes de un agente de código sobre el repositorio en rama `develop`, HEAD `673eda92fcb18626788804a3a48b41730e6784fb` (merge de PR #28), estado Git limpio. Trabajo **de solo lectura**: no se modificó nada y **no se ejecutaron tests** — lo que se dice sobre tests sale de leer su código, no de ejecutarlos. Módulos reales: `contracts`, `core`, `crypto`, `ai`, `api`, `identity`, `app`; **no existe módulo `convocatoria`**. Las rutas y líneas citadas son las de los informes.

#### 6.9.1 H-D3.2 — `campaignRef` en `PhysicalAsset`

**Hechos:**
- `PhysicalAsset` (`core/.../domain/physicalasset/PhysicalAsset.java:22-43`) no tiene `campaignRef` ni `fundId`. Ningún payload de activo (`AssetRegistered(V2)Payload`, `AssetSplit(V2)Payload`) los contiene.
- En `core/src/main`, `campaignRef` solo aparece en `Fund` (y sus payloads/proyección), y ahí es **opcional**: `Fund.registerFund`/`Fund.clearFundsGenesis` solo exigen `organizationRef` e importe positivo.
- Camino A (`PhysicalAssetCommandService.registerPhysicalAsset`, :105-171): carga el `Fund` solo para validar `organizationRef` (:173-184); no lee `campaignRef`. El `fundId` va solo en el `OutboxMessage` de la saga, no en el evento del activo.
- Camino B (`registerPhysicalAssetFromDonation`, :189-238): sin `Fund` ni allocation; guarda `organizationRef`, `donorRef`, `donationRef`; ninguna referencia a convocatoria.
- Split (`splitPhysicalAsset`, :243-280 → `PhysicalAsset.split`, :171-198): copia `organizationRef`, `donorRef`, `donationRef`, `rootAssetRef`; no copia `allocationId`, `fundId` ni `campaignRef`.
- La única cadena de recuperación es activo → `allocationId` → read model → `fundId` → `Fund.campaignRef`: pasa por proyecciones (no son fuente de verdad) y puede terminar en `null`.
- No hay endpoints que creen activos; `registerPhysicalAsset`/`splitPhysicalAsset` solo se llaman desde tests. Ningún test relaciona un activo con una convocatoria.

**Conclusión:** ningún activo conoce hoy su convocatoria; sin corrección no se pueden evaluar ni la capacidad contextual del administrador responsable, ni el respaldo del `REPRESENTATIVE`, ni la validación de `IN_KIND` (D3). Decisión: §6.10.

#### 6.9.2 C-D2.4 — Autorización contextual

**Hechos:**
- `RoleAuthorizationPolicy.authorize(AuthorizationPrincipal, CommandType)` (`core/.../authorization/RoleAuthorizationPolicy.java:10-29`): switch exhaustivo; `REGISTER_PHYSICAL_ASSET`, `REGISTER_PHYSICAL_ASSET_FROM_DONATION`, `SPLIT_PHYSICAL_ASSET` y `DELIVER_ASSET` exigen `EMPLOYEE` (:21). Ninguna rama para `REPRESENTATIVE`.
- Flujo actual (`PhysicalAssetCommandService.authorize`, :55-69): `SystemActor`/`ExternalActor` se saltan la autorización; `HumanActor` → `resolvePrincipal` → `OrganizationBoundaryPolicy.assertBelongs` → `RoleAuthorizationPolicy.authorize`. Los cuatro métodos de activo la invocan (:84, :127, :206, :261); `FundCommandService` también (:51-65).
- No existen `REPRESENTATIVE_BACKUP_COMMANDS`, `CampaignAssignment`, `ConvocatoriaReadPort` ni ninguna comprobación de responsable o de `EMPLOYEE` activo. `contracts` solo contiene `IdentityPrincipalPort`, `AuditFactsPort`, `NarrativeReadPort` y `HashPort`. `app/src/main` no orquesta identidad+convocatoria+core.
- Tests (`RoleAuthorizationPolicyTest.java:66-68`, `RegisterPhysicalAssetFromDonationIntegrationTest:187`, `PhysicalAssetCommandServiceAuthorizationTest:106`) confirman el **rechazo** del `REPRESENTATIVE`. No hay test de autorización para `splitPhysicalAsset` ni ningún test de respaldo.

**Conclusión:** el respaldo del `REPRESENTATIVE`, dado por decidido en la documentación, **no existe**; hoy el código lo rechaza en todos los casos. Decisión sobre el mecanismo: §6.10.

#### 6.9.3 `clearFundsGenesis` — idempotencia (pendiente de máxima severidad de `estado-fase6.md`)

**Hechos (leídos del código, no ejecutados):**
- `FundCommandService.clearFundsGenesis` (:83-97) existe. No hay webhook, `DonationIntent`, `providerEventId` ni `paymentSessionId` en ningún módulo; no hay llamador de producción, solo tests.
- Comprobación previa `processedCommandRepository.exists(commandId)` → return (:84-86), fuera de la transacción y del bucle de reintentos.
- `TransactionalEventPublisher.appendAndOutbox` (`@Transactional`): `tryClaim(commandId)` (findAndModify con upsert sobre `processed_commands._id`) y, si falla, return silencioso; luego `append` con `expectedVersion = 0` y outbox.
- `CommandRetryTemplate`: 3 intentos, solo ante `ConcurrencyConflictException`; al agotarse, `ConcurrencyRetryExhaustedException`.
- Índice único `{streamId, sequence}` en `event_store`: se crea con `auto-index-creation: true` en `app`; no en la configuración de tests de `core`.

**Comportamiento según el código:**

| Escenario | Resultado |
|---|---|
| Mismo `commandId` tras éxito | No-op silencioso, **sin validar que el contenido coincida** |
| `commandId` distinto, mismo `fundId` | Conflicto de secuencia → 3 reintentos → **error**, no no-op (no duplica si el índice existe) |
| `commandId` y `fundId` distintos | **Duplicación**: segundo `Fund` con fondos liquidados (nada lo impide porque `DonationIntent` no existe) |
| Fallo parcial dentro de la transacción | Rollback completo (probado para `confirmAllocation`, no para `clearFundsGenesis`) |

No hay ningún test de reintento, duplicado o mismo `fundId` para `clearFundsGenesis`. **Estado: NO CONFIRMADO.** Reglas de diseño derivadas: §6.10.

#### 6.9.4 Documentación desactualizada y defectos detectados (no se corrigen sin aprobación)

- `golden-path.md` §5.2 dice que ningún comando de `PhysicalAsset` invoca las políticas y que `deliverAsset` no tiene chequeo: **falso** (los cuatro métodos llaman a `authorize`).
- `golden-path.md` §5.3 / :126: `clearFundsForPledge` sí existe (`FundCommandService.java:99-118`) y `FundCommandService` sí integra `RoleAuthorizationPolicy` (:51-65).
- `fase-6-estructura-y-perimetro-convocatoria.md` §3.5: `REGISTER_PHYSICAL_ASSET_FROM_DONATION` sí está implementado y autorizado para `EMPLOYEE`.
- `fase-6-estructura-y-perimetro-convocatoria.md` §3.4 afirma que no hay `MongoTransactionManager`; `app` sí lo declara (ya corregido por §5 de este resumen y por ADR-037 §2.3).
- `api-contract-matrix.md:53` anuncia "EMPLOYEE/REPRESENTATIVE backup"; `hallazgos-front-fase2.md` (≈ línea 190) afirma que el backend permitiría el split al `REPRESENTATIVE`: ambos contradicen el código.
- `DonationProjectionHandler.java:192` dice que el hijo de un split se registra con su propio `ASSET_REGISTERED`, pero ese evento nunca se emite (coherente con `SplitPhysicalAssetSagaPolicy` pendiente según `documento-maestro-proyecto.md`).
- `ProcessedCommandIdempotencyIntegrationTest.java:106` crea el índice único sobre la colección `events` en lugar de `event_store`: la protección por índice no está donde el test cree probarla.

Tratamiento aprobado: correcciones documentales como `chore/` después de la enmienda; el índice del test como `fix/` con test de regresión cuando se apruebe tocar código.

#### 6.9.5 Propagación de `appendAndOutbox`

**Hechos:**
- `core/src/main/java/com/traceability/core/application/service/TransactionalEventPublisher.java:25`: `@Transactional` sin atributos (`org.springframework.transaction.annotation.Transactional`, importado en la línea 8) → valores por defecto: `Propagation.REQUIRED`, `Isolation.DEFAULT`, rollback ante `RuntimeException` y `Error`. Es un `@Service` invocado a través del proxy de Spring.
- Llamadores de producción: solo en `core` — `FundCommandService` (líneas 78, 94 [`clearFundsGenesis`], 115, 136, 155, 159, 179, 181, 204, 206) y `PhysicalAssetCommandService` (90, 93, 161, 228, 269). Ninguno en `app` ni en otros módulos.
- Ningún llamador tiene `@Transactional` ni usa `TransactionTemplate`. En `core/src/main`, la única `@Transactional` es la de `TransactionalEventPublisher.java:25`. El `MongoTransactionManager` lo aporta `app` (`TraceabilityInfrastructureConfig.java:14`).

**Conclusión registrada:** `appendAndOutbox` está correctamente preparado para participar en una transacción externa mediante `Propagation.REQUIRED` (no es `REQUIRES_NEW`), pero hoy no existe ningún llamador transaccional externo, así que cada llamada abre su propia transacción. La atomicidad de `STRICT` que exige ADR-037 depende de la implementación futura del orquestador. **Estado de `STRICT`: diseño aprobado, integración pendiente.** No es un defecto del código actual ni requiere rediseño ahora.

**Requisito de implementación para el orquestador (derivado, no verificado en ejecución):** `FundCommandService.clearFundsGenesis` ejecuta su propia comprobación previa (`exists(commandId)`) y su propio bucle de reintentos (`CommandRetryTemplate`) **dentro** del método. Si el orquestador lo invoca dentro de una transacción externa, un conflicto de escritura provocará rollback de esa transacción externa completa (con `REQUIRED`, la transacción interna es la misma), y reintentar dentro de ella no la recupera. Por tanto, **el reintento debe envolver la transacción completa del orquestador**, no vivir dentro de ella. El comportamiento real de esta combinación no está verificado y debe cubrirse con un test de Testcontainers al implementar el orquestador.

### 6.10 Decisiones técnicas aprobadas por el equipo — 30 sept 2026 (tras la verificación de código)

*Formalizada en la Enmienda 1 de ADR-037 (APROBADA el 2026-09-30) e integrada en ADR-037 por la consolidación del 2026-10-03. El texto de esta sección se conserva como registro de la decisión humana.*

**Aprobación humana explícita del equipo. No constituye enmienda normativa de ningún ADR (ver encabezado de §6).**

| Punto | Decisión aprobada |
|---|---|
| `campaignRef` en `PhysicalAsset` | Camino A: heredado del `Fund`. Camino B: recibido en el comando. Split: el hijo lo hereda del padre. **Inmutable tras el registro**; un cambio de convocatoria sería un flujo de dominio propio, nunca una mutación del activo |
| Cómo se obtiene la convocatoria de un activo | Nunca se infiere recorriendo proyecciones ni relaciones indirectas |
| Activos sin convocatoria (legados, o de un `Fund` con `campaignRef = null`) | Solo autoridad ordinaria de `EMPLOYEE`; sin respaldo del `REPRESENTATIVE` ni capacidad contextual del `ADMINISTRATOR` |
| Puerto de autorización contextual | Nuevo, definido en `contracts`, implementado por `convocatoria`, consumido por `core` (mismo patrón que `IdentityPrincipalPort`). No amplía `IdentityPrincipalPort` |
| Alcance del puerto | Mínimo y cerrado: solo responde "¿puede este principal ejercer esta capacidad contextual sobre esta convocatoria?"; nunca un servicio genérico de consultas sobre convocatorias o miembros |
| Ventana entre comprobar y ejecutar | Aceptada explícitamente (la misma que ya existe con los roles, resueltos en cada petición) |
| Idempotencia financiera | La transición `DonationIntent PENDING → CONFIRMED` es condicional e idempotente y actúa como barrera **antes** de cualquier efecto financiero sobre el ledger, en la misma transacción *(histórico, sustituido por la Enmienda 2 de ADR-037 §3.3 — §6.20)* |
| `commandId` del webhook | Derivado de forma determinista de la intención; nunca generado en cada entrega |
| Correcciones documentales (§6.9.4) | Después de la enmienda |
| Fix del índice del test | Cuando se apruebe tocar código |
| `appendAndOutbox` | Verificado (§6.9.5): `REQUIRED`; `STRICT` queda como diseño aprobado con integración pendiente, condicionado a que el reintento envuelva la transacción completa del orquestador |

Implica enmienda a ADR-029 (nueva versión de `ASSET_REGISTERED` y del split con upcaster, e inmutabilidad de `campaignRef`), a ADR-032 (puerto contextual) y a ADR-037 (idempotencia del webhook y del ledger, requisito transaccional del orquestador).

### 6.11 Cierre de D4 y `REJECT_EXCESS` — aprobado por el equipo el 30 sept 2026

*Formalizada en la Enmienda 1 de ADR-037 (APROBADA el 2026-09-30) e integrada en ADR-037 por la consolidación del 2026-10-03. El texto de esta sección se conserva como registro de la decisión humana.*

**Aprobación humana explícita del equipo. No constituye enmienda normativa de ningún ADR (ver encabezado de §6).**

| Punto | Decisión aprobada |
|---|---|
| H-D4.2 Vencimiento | Toda intención de `BANK_TRANSFER` tiene fecha/hora de expiración fijada al crearla. Vencida, no se puede confirmar como pago válido mediante esa intención; si el donante quiere continuar, se crea una intención nueva. El vencimiento **no** decide el destino del dinero que llegue tarde (eso es H-D4.3). La duración es un parámetro de configuración de implementación, no una constante de dominio. Separado del estado `EXPIRED-UNKNOWN` de la pasarela y de D1 (D1 protege una intención frente al cierre de la convocatoria; aquí vence la propia intención). El efectivo se registra en persona y no tiene intención con vencimiento |
| H-D4.4 Referencia única | Una referencia de pago no puede usarse para confirmar más de una operación monetaria. El alcance exacto del índice (por organización y medio, u otro) se fija en el contrato de transferencia, cuando se sepa qué identificador y contexto aporta realmente el banco |
| H-D4.5 Cambio de medios | `acceptedPaymentMethods` sigue exactamente la misma política de configuración que `acceptedDonationTypes`: edición directa antes de la primera donación; después, solo con solicitud y aprobación; nunca modifica retrospectivamente operaciones ya creadas; una convocatoria `CLOSED` no admite cambios. Tipo de donación y medio de pago siguen siendo configuraciones distintas |
| `REJECT_EXCESS` | Rechaza **completa** la donación que haría superar la meta; no existe aceptación parcial |
| H-D4.3 Dinero no aceptable | **Pendiente explícito de la enmienda**, no solución final. Congelado lo que se sabe: no entra al `Fund`; no entra al ledger; queda registrado fuera de ambos; tiene resolución manual trazable. Pendiente: si la resolución será devolución, conciliación u otro mecanismo, por sus implicaciones de PII |

### 6.12 Decisiones A/B derivadas del diff — aprobadas por el equipo el 30 sept 2026

*Formalizada en la Enmienda 1 de ADR-037 (APROBADA el 2026-09-30) e integrada en ADR-037 por la consolidación del 2026-10-03. El texto de esta sección se conserva como registro de la decisión humana.*

**Aprobación humana explícita del equipo. No constituye enmienda normativa de ningún ADR (ver encabezado de §6).**

**A. Validación de `IN_KIND`**
- La aceptación de `IN_KIND` se valida contra la configuración vigente de la convocatoria.
- Es una regla de negocio, no de autorización.
- No se amplía el puerto contextual de §6.10 para resolverla.
- La ubicación concreta queda para el diseño del caso de uso (pendiente explícito H-D3.4).

**B. Operaciones de responsables**
- `AssignEmployeeToCampaign` y `DesignateAdministratorAsCampaignResponsible` son operaciones conceptualmente separadas; no se fuerza un comando genérico basado en `actingRole`. Pueden compartir infraestructura interna, pero sus invariantes y su autorización son explícitos por separado.
- Ambas generan una asignación con `actingRole`; `actingRole` sigue siendo parte del registro de dominio (§6.7). Se separa la operación, no el registro.
- La restricción de una convocatoria activa se aplica solo a `EMPLOYEE`; un `ADMINISTRATOR` puede ser responsable de varias.
- `RemoveResponsible(campaignRef, responsibleRef, replacementRef?)` permanece como operación única, con reemplazo dentro de la misma transacción (nunca una ventana con cero responsables). Si hay reemplazo, se aplican las reglas de la operación que corresponda a su tipo (empleado o administrador).
- El contrato HTTP actual (`POST /campaigns/{campaignRef}/employees`, ADR-041 y `claude/front-fase2.md`) deberá actualizarse; la enmienda lo registra como dependencia, no lo resuelve.
  *(Nota de la consolidación del 2026-10-03: la ruta `claude/front-fase2.md` no existe en el repositorio; se conserva como la cita la fuente original.)*

**Pago**
- La correlación mediante `paymentSessionId` (ADR-037 §2.6) permanece como diseño cerrado.
- El webhook y el proveedor son integración pendiente (H-D4.7). No se reabre el diseño financiero por eso.

### 6.13 Gate de implementación y decisión B2 — aprobado por el equipo el 30 sept 2026

*Formalizada en la Enmienda 1 de ADR-037 (APROBADA el 2026-09-30) e integrada en ADR-037 por la consolidación del 2026-10-03. El texto de esta sección se conserva como registro de la decisión humana.*

**Aprobación humana explícita del equipo. No constituye enmienda normativa de ningún ADR (ver encabezado de §6).**

**Resultado del gate de implementación (revisión de solo lectura del 30 sept):** para empezar a programar el dominio y los casos de uso de Convocatoria solo faltaban dos cierres: B1 (aprobar la Enmienda 1, con N1–N10 y una regla de precedencia documental) y B2 (idempotencia de los comandos de escritura de Convocatoria, hallazgo R4 de `hallazgos-front-fase2.md`). P1–P6, el puerto contextual, `campaignRef` en `PhysicalAsset`, el contrato HTTP, el webhook y el orquestador de `STRICT` no bloquean el primer corte; bloquean solo las integraciones que dependen de ellos.

**B2 — decisión aprobada:**

| Punto | Decisión aprobada |
|---|---|
| Mecanismo | Reutilizar el patrón existente de `core`: `commandId` + registro de comandos procesados (`processed_commands` / `tryClaim`). No se crea un mecanismo de idempotencia propio de Convocatoria |
| Alcance mínimo | Crear convocatoria; asignar empleado; designar administrador; retirar responsable; cerrar convocatoria; comandos de escritura de configuración |

**Precisiones que se incorporan en la enmienda (§3.5), pendientes de su aprobación:**
- Se reutiliza el patrón, no el código ni la colección de `core`: `convocatoria → contracts` sigue siendo su única dependencia directa, así que el módulo tiene su propio registro con el mismo mecanismo, dentro de la misma transacción que la escritura de dominio.
- N12: un duplicado devuelve el resultado de la ejecución original (por ejemplo, el `campaignRef` de la convocatoria ya creada), sin repetir el efecto.
- El patrón de `core` no comprueba que el contenido del duplicado coincida con el original; la enmienda no añade esa comprobación.

### 6.14 Auditoría de cierre y cierre manual — aprobado por el equipo el 30 sept 2026

*Formalizada en la Enmienda 1 de ADR-037 (APROBADA el 2026-09-30) e integrada en ADR-037 por la consolidación del 2026-10-03. El texto de esta sección se conserva como registro de la decisión humana.*

**Aprobación humana explícita del equipo. No constituye enmienda normativa de ningún ADR (ver encabezado de §6).**

**Resultado de la auditoría de cierre (solo lectura, 30 sept):** la enmienda está lista para `implementation_plan.md` una vez aprobada. Sin contradicciones que obliguen a rediseñar; P1–P6 siguen pendientes; ningún pendiente aparece resuelto por accidente. Cuatro puntos se cerraron así:

| Punto | Decisión aprobada |
|---|---|
| N2 — ledger | El ledger existe solo si la convocatoria acepta `MONETARY`; se crea al crear la convocatoria o en la transición que añade `MONETARY`, en la misma transacción. Modifica "1:1" de ADR-037 §2.2 |
| N12 — idempotencia | El registro de comandos procesados guarda la referencia al resultado original (al crear: `campaignRef` + `publicCode`) para devolverlo ante un duplicado |
| N10 — distinción pública | Se declara la dependencia hacia `core`/ADR-040 (enmienda §7.4), sin convertirla en trabajo de Convocatoria |
| P1–P6 | Siguen pendientes; no se inventan soluciones ahora |

**Cierre manual — decisión aprobada:**

| Situación | Semántica |
|---|---|
| Cierre manual | Operación explícita que cambia `OPEN → CLOSED` |
| Quién puede cerrar | `ADMINISTRATOR` de la organización |
| Cierre automático por fecha | No se introduce ahora (no estaba decidido en ADR-037) |
| `DonationIntent` con convocatoria `CLOSED` | No se crean nuevas intenciones |
| Intenciones existentes | No se invalidan por el mero cierre (D1) |
| Reapertura | Fuera del contrato actual |
| `CLOSED` | No permite cambios de configuración |

- La ventana de fechas no se mezcla con el cierre manual: son mecanismos distintos. Su efecto sobre la creación de intenciones queda como **pendiente P7** de la enmienda; mientras no se decida, la precondición vigente es solo `status = OPEN` (ADR-037 §2.6).
- El cierre al alcanzar la meta (`CLOSE_ON_TARGET` + `CLOSE`) es otro mecanismo y no cambia.
- El cierre de la convocatoria sigue siendo distinto del vencimiento de una intención `BANK_TRANSFER`.

**Siguiente paso acordado:** aprobar la enmienda (N1–N12) → `implementation_plan.md` → revisión única → código. No se hace otra auditoría conceptual de Convocatoria.

### 6.15 Cierre de decisiones del primer corte — aprobado por el equipo el 1 oct 2026

**Aprobación humana explícita del equipo**, tomada sobre la revisión técnica de `implementation_plan.md` (30 sept–1 oct, solo lectura del código en `develop`, HEAD `673eda92`). Estas decisiones acotan el **primer corte** de implementación de Convocatoria; no reescriben la enmienda.

| Punto | Decisión aprobada |
|---|---|
| Estado de la Enmienda 1 | APROBADA (30 sept, N1–N12). El encabezado del documento pasa de BORRADOR a APROBADA |
| R1 — datos mínimos | `title` obligatorio; `description` opcional; `startDate` y `endDate` obligatorias con `startDate < endDate`; `currency` obligatoria cuando la convocatoria acepta `MONETARY`. Las fechas describen la convocatoria y **no limitan la creación de `DonationIntent` en el primer corte** |
| P7 — fechas | Sigue **pendiente** como regla futura. En el primer corte la precondición de creación de intención es solo `status = OPEN` (enmienda §3.4) |
| ID — creación de `DonationIntent` | Idempotente con el mecanismo del módulo (`commandId` + registro de comandos procesados propio, misma transacción). Amplía el alcance de B2 (§6.13). Un duplicado devuelve el resultado original (`intentId`). El transporte HTTP del `commandId` sigue en ADR-041 §7-A.4 |
| R3 — solicitud de cambio de configuración | **Fuera del primer corte.** No se implementa hasta definir explícitamente la matriz de autorización pendiente |

**Precisión sobre R3 (corrige la revisión técnica del 1 oct):** quién **aprueba** ya estaba decidido en la enmienda §3.2 [DECISIÓN] — otro `ADMINISTRATOR` o el `REPRESENTATIVE`, siempre distinto del solicitante; sin aprobador válido, el cambio se bloquea; PaxFide nunca aprueba — y el conflicto de versión también (§6.8). La revisión técnica afirmó por error que los roles no estaban enumerados. Lo que realmente queda abierto para la solicitud de cambio es: quién puede **solicitar**, quién puede **rechazar**, si el solicitante puede **retirar** su solicitud, y la **cancelación** de una solicitud pendiente al cerrar la convocatoria.

**Resueltos sin decisión nueva (registrados para no reabrirlos):**
- R2: una convocatoria puede existir con 0 responsables hasta su primera asignación (ADR-037 §2.5); "nunca 0" protege el retiro. La frase "nunca 0 responsables" de §2 es conceptual y queda subordinada al ADR.
- X1: `verificationStatus` no existe en código; Convocatoria declara un puerto de salida propio y la fuente del dato es ADR-038.
- X2: `IdentityPrincipalPort.resolvePrincipal` sirve para el destinatario sin cambiar el contrato; dos huecos para ADR-038 (cuentas `INACTIVE` no filtradas; `AccountNotFoundException` de `identity` cruza la frontera).

**Corrección de §2 (sin cambio de decisión):** la frase "`CommandRetryTemplate` para conflictos de concurrencia de dominio … reutilizados" no vale para el camino del orquestador de `STRICT`: dentro de su transacción, `clearFundsGenesis` no puede reintentar con `CommandRetryTemplate` (reintentaría sobre una transacción abortada y descarta la causa transitoria). Queda como condición previa del orquestador en `core`/`app` (T1, `implementation_plan.md` §8).

**Siguen abiertos sin bloquear el primer corte:** P1–P7; R4 (`CLOSE_ON_TARGET` + `CLOSE`, con el orquestador); webhook; `campaignRef` en `PhysicalAsset` (ADR-029); puerto contextual (ADR-032); orquestador `STRICT`.

**Siguiente paso:** aprobación humana de `implementation_plan.md` (revisión 2) → código.

### 6.16 Aclaración técnica B-1 — dirección de dependencias con `app` (1 oct 2026)

**Naturaleza:** aclaración técnica derivada de la verificación del repositorio (grafo Maven y ensamblaje de `app`, `develop` @ `673eda92`), **no** decisión humana de dominio. No cambia el alcance del primer corte. Registrada en `implementation_plan.md` revisión 2.2 (§2, §11, Tarea 1).

| Punto | Estado |
|---|---|
| Dependencia directa de `convocatoria` | Solo `contracts` (ADR-037 §2; sin cambios) |
| `convocatoria → app`, `convocatoria → core`, `convocatoria → identity` | Prohibidas siempre |
| `app → convocatoria` en el primer corte | **No se añade**: no existe orquestador y no existe implementación real de `OrganizationVerificationPort`; con la dependencia, el contexto completo de `app` (`ApplicationContextLoadTest`) no arrancaría |
| `app → convocatoria` después | **Se añadirá y será necesaria** cuando se implemente la composición/orquestación que la requiera (en particular `STRICT` y los adaptadores que se componen en `app`). No contradice la arquitectura: `app` es el módulo de composición, ya depende de `core`, `identity`, `crypto`, `ai` y `api`, y no hay autoconfiguración que descubra módulos fuera de su classpath |

Corrige la redacción anterior de B-1, que podía leerse como "Convocatoria no depende de `app`" en sentido amplio: la restricción permanente es la dirección `convocatoria → app`, no `app → convocatoria`.

### 6.17 Cierre de C-01 — semántica de `CONFIRMED` y fallo de aplicación de fondos — decisión humana del 2 oct 2026

**Aprobación humana explícita**, tomada el 2026-10-02 sobre `auditoria-c01-postcorreccion.md` (informe temporal, no canónico). **Esta sección es el primer y único registro documental de esta decisión.** Acota el **primer corte** de Convocatoria, mientras `Fund` esté fuera de él (`implementation_plan.md` §1.3, §9.3). No reescribe la Enmienda 1 (mismo criterio que §6.15).

| Punto | Decisión aprobada |
|---|---|
| DH-1 — `confirmDonationIntent()` por sí sola | **No como flujo financiero completo.** Puede existir como transición propia del ciclo de vida de la intención, pero no afirma ni garantiza que los fondos se aplicaron |
| DH-2 — significado de `CONFIRMED` | La `DonationIntent` fue confirmada y aceptada dentro del flujo de intención. **No** significa que el importe esté aplicado al ledger ni que exista todavía un `Fund`. El estado financiero efectivo queda fuera de esta operación mientras `Fund` siga fuera del corte. No existe la garantía "`CONFIRMED` ↔ fondos aplicados" y no debe introducirse |
| DH-3 — rechazo del ledger | La intención **no** queda confirmada como consecuencia de una aplicación de fondos que el ledger rechazó. El rechazo es distinguible de `CONFIRMED`. Cómo se persiste el dinero rechazado y su registro pertenece a P1 / flujo financiero posterior: no se crea aquí ninguna entidad, estado ni transacción |
| DH-4 — propagación del fallo | La frontera que ejecuta la aplicación de fondos no captura en silencio una excepción de dominio para convertirla en una confirmación falsa. El fallo es visible para el orquestador y no produce una transición que afirme que los fondos se aplicaron. La composición transaccional definitiva del flujo externo queda en el módulo/orquestador que corresponda según la arquitectura documentada (ADR-037 §2.3; `implementation_plan.md` §8); no se traslada a `convocatoria` por conveniencia |
| DH-5 — registro | La "decisión humana del 2026-10-02" que citaba el Javadoc de `CampaignFundingLedgerService` **no constaba en ningún documento** y no se presenta como decisión histórica. La decisión vigente es esta sección; debe quedar registrada aquí antes de modificar el comportamiento |

**Lectura con la Enmienda 1 (sin cambio de decisión):** §5.3 (la transición es barrera *antes* de cualquier efecto financiero, en la misma transacción) sigue vigente: exige que todo efecto financiero vaya precedido por la transición, no que toda transición vaya seguida de un efecto. N9 (la confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`) describe el flujo completo con `Fund`, fuera del corte; DH-1 y DH-2 están acotadas a ese periodo.

**Puntos abiertos detectados al reauditar C-01 contra esta decisión.** *Superados por §6.18: C01-B estaba resuelto documentalmente (ADR-037 §2.3; `implementation_plan.md` §7.2, §8, §11) y C01-A no tiene camino de producción. Se conserva el texto original como registro.*

- **C01-A — Barrera consumida sin efecto.** El código usa la transición `PENDING → CONFIRMED` como única marca de que el efecto sobre el ledger ya ocurrió (`applyFundsForIntent` devuelve `false` y no aplica nada sobre una intención `CONFIRMED`; Enmienda §5.3). Con DH-1 y DH-2, una intención confirmada mediante `confirmDonationIntent()` está `CONFIRMED` sin fondos y, desde ese momento, el módulo no ofrece ningún camino para aplicar su importe. El `false` resultante es indistinguible de "ya aplicado" (reproducido: `auditoria-c01-postcorreccion.md` §8, Q1, Q2, Q5). Falta decidir si eso es aceptable en el corte o qué marca la aplicación de fondos, sin inventar estados (DH-3).
- **C01-B — Ubicación del acoplamiento intención ↔ ledger.** `implementation_plan.md` §7.2 y §8 asignan al orquestador de `app` la regla "solo se toca el ledger si la transición se aplicó en la misma transacción", y §4.4 no contiene una transacción combinada. El código actual compone transición y ledger dentro de `convocatoria` (`CampaignFundingLedgerService.applyFundsForIntent`, que se une a la transacción externa si existe), apoyándose en la decisión no registrada de DH-5. Falta decidir si ese acoplamiento se mantiene en `convocatoria` o vuelve al orquestador (DH-4). Hecho relevante: sin el acoplamiento en el módulo, la doble aplicación por intención vuelve a ser posible para cualquier llamador que no componga la barrera (`auditoria-c01-idempotencia-fondos.md` §6).

### 6.18 Cierre de C-01 — formulación final de la decisión humana (2 oct 2026)

*Sustituida por §6.19, que es la decisión vigente. Las frases de esta sección sobre la ubicación de la composición y el límite del corte fueron redactadas por el agente como inferencias, no como decisión humana; se conservan como registro.*

**Aprobación humana explícita del 2026-10-02**, posterior a §6.17 y a la segunda auditoría de `auditoria-c01-postcorreccion.md`. Sustituye los puntos abiertos C01-A y C01-B de §6.17. No reabre la Enmienda 1: §5.3 (barrera `PENDING → CONFIRMED` antes de cualquier efecto financiero sobre el ledger, en la misma transacción) sigue vigente sin cambios.

| Punto | Decisión aprobada |
|---|---|
| DH-1 — confirmación independiente | `confirmDonationIntent()` **puede ejecutarse como operación independiente**. Confirmar significa que la intención se confirmó válidamente y que la transición queda registrada; no significa fondos aplicados, `Fund` existente ni dinero contabilizado. No se crea una dependencia artificial entre confirmar y aplicar fondos |
| DH-2 — semántica de `CONFIRMED` | Exclusivamente: "la intención de donación fue confirmada válidamente". La aplicación de fondos es posterior y puede estar fuera del corte. No se cambia el modelo de estados para compensar la ausencia de `Fund`; no se crean estados nuevos |
| DH-3 — fallo del ledger | Si `applyFundsForIntent()` intenta aplicar y el ledger lo rechaza por una regla de negocio: la aplicación no es exitosa, no hay confirmación falsa y la excepción de dominio conserva su semántica. El registro del dinero rechazado pertenece a P1 / orquestación; no se crea ninguna máquina de estados |
| DH-4 — excepciones y transacciones externas | `applyFundsForIntent()` no captura en silencio una excepción de aplicación ni permite que un llamador externo convierta el resultado en éxito: la excepción se propaga, la operación conserva sus garantías transaccionales y no queda una confirmación producida por la captura. Sin compensaciones en `app` ni sagas en `convocatoria` |
| DH-5 — referencia inexistente | La "decisión humana del 2026-10-02" citada en el Javadoc de `CampaignFundingLedgerService` no tiene fuente canónica anterior: no es autoridad y se corrige la referencia. No se crea ningún ADR retroactivo |

**Ubicación de la composición (no es decisión nueva):** la composición del flujo externo (ledger + `Fund.FUNDS_CLEARED` + outbox) pertenece al orquestador de `app` (ADR-037 §2.3; `implementation_plan.md` §7.2, §8, §11). DH-3 y DH-4 fijan el contrato de `CampaignFundingLedgerService.applyFundsForIntent()` como la operación con la que `convocatoria` aplica los fondos de una intención detrás de la barrera, uniéndose a la transacción del orquestador cuando existe. La operación de solo-ledger que describía `implementation_plan.md` §8 se sustituye por ella (plan rev. 2.4).

**Límite del corte (no decidido; fuera de C-01):** una intención confirmada mediante `confirmDonationIntent()` ya no está `PENDING`, así que `applyFundsForIntent()` no aplica su importe y devuelve `false`. Ese `false` significa "no se aplicó nada en esta llamada", no "ya se aplicó". Aplicar después los fondos de una intención confirmada pertenece al flujo financiero posterior (orquestador / `Fund`), fuera de este corte, y no se resuelve con estados nuevos (DH-2).

### 6.19 Cierre de C-01 — decisión humana vigente (2 oct 2026)

**Aprobación humana explícita del 2026-10-02.** Es la única fuente válida de las decisiones DH-1–DH-5 de C-01: sustituye §6.17 y §6.18. No reabre la Enmienda 1.

| Punto | Decisión aprobada |
|---|---|
| DH-1 | `confirmDonationIntent()` puede ejecutarse de forma independiente. Confirmar una intención es una transición de estado del dominio y no implica por sí misma que los fondos ya se hayan aplicado |
| DH-2 | Mientras `Fund` siga fuera del alcance de este corte, `CONFIRMED` significa exclusivamente que la intención fue confirmada correctamente; no que los fondos estén aplicados al ledger ni que exista todavía un `Fund`. La aplicación de fondos es una operación posterior y separada |
| DH-3 | Si `applyFundsForIntent()` no puede aplicar los fondos por superar el límite del ledger, la intención no queda `CONFIRMED` como consecuencia de ese intento. El rechazo se conserva como resultado del intento de financiación y la intención permanece en un estado que permite un nuevo intento válido. No se inventan estados nuevos; si hiciera falta uno, se reporta como hueco documental antes de implementarlo |
| DH-4 | Si la aplicación de fondos se ejecuta dentro de una transacción externa y falla, la excepción se propaga al orquestador. El módulo no la captura en silencio ni convierte un fallo de aplicación en una confirmación exitosa |
| DH-5 | La frase de Javadoc "decisión humana del 2026-10-02" no es documentación canónica ni autoridad. La decisión válida es esta sección |

**Cómo queda el comportamiento (verificado en código y tests, `implementation_plan.md` rev. 2.5):**
- **Rechazo:** la intención queda `PENDING`, el estado que el dominio ya contempla para una intención no confirmada. Ese estado permite un nuevo intento de aplicación o una confirmación independiente.
- **Resultado del intento:** el rechazo se conserva como la excepción de dominio que recibe el llamador. No queda persistido: el registro del dinero no aceptable es P1 (Enmienda §3.3, N4), no implementado.
- **Transacción externa:** `applyFundsForIntent` participa en ella y la deja marcada para rollback ante cualquier excepción. Un llamador que capture la excepción no puede confirmar la transición (DH-4).

**Contradicciones detectadas al registrar esta decisión.** *Renombradas K-1 y K-2 el 2026-10-02 para no confundirlas con las decisiones humanas F-1 y F-2 de §6.20, que las resuelven (Enmienda 2 de ADR-037).*
- **K-1 (resuelta por §6.20) — DH-2 frente a la Enmienda §5.3 y `applyFundsForIntent`.** DH-2 dice que la aplicación de fondos es "una operación posterior y separada". DH-3 presupone que el intento de aplicación es el que podría confirmar la intención, y la Enmienda §5.3 [DECISIÓN] exige que la transición sea la barrera "en la misma transacción" que el efecto sobre el ledger. Si "posterior" significa posterior a una confirmación ya hecha, una intención `CONFIRMED` no puede financiarse sin otra barrera: hoy `applyFundsForIntent` devuelve `false` y no aplica nada. Habría que actualizar la Enmienda §5.3, `implementation_plan.md` §7.2 y §8, `CampaignFundingLedgerService.applyFundsForIntent` y el modelo de `DonationIntent`, que necesitaría una marca de financiación. No se cambia nada hasta que se decida.
- **K-2 (resuelta por §6.20) — DH-1 frente a la Enmienda §5.2 (N8, N9).** N9: "La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`" (`ADMINISTRATOR`, ADR-032). `confirmDonationIntent()` registra los datos de esa confirmación (N8) sin `CLEAR_FUNDS_AS_GENESIS` y sin autorización. No se materializa mientras `Fund` esté fuera de corte (`implementation_plan.md` §9.3: la confirmación manual de `BANK_TRANSFER` no es desplegable). Habría que actualizar la Enmienda §5.2 / N9 o ADR-032, y `confirmDonationIntent` / `ConfirmDonationIntentCommand` (autorización), antes de exponer la operación o de integrar `Fund`.

### 6.20 Cierre del flujo `CONFIRMED` → aplicación de fondos — decisiones humanas del 2 oct 2026

*Formalizada en la Enmienda 2 de ADR-037 (BORRADOR, no integrada en ADR-037). D2 está además desarrollada en ADR-043 (Propuesto), documento de otro bloque (§6.21). Hasta su aprobación, estas decisiones no modifican ADR-037 (encabezado de §6); el conflicto con E1 §5.3 y N9 está registrado como CD-03 (§0.5).*

**Aprobación humana explícita del 2026-10-02.** Fuente de las decisiones que formaliza la **Enmienda 2 de ADR-037** (`ADR-037-enmienda-2-convocatoria.md`, BORRADOR pendiente de aprobación del texto) y **ADR-043** (recuperación, Propuesto). Sustituye, en lo que se contradigan, a §6.17, §6.18 y §6.19.

| # | Decisión aprobada |
|---|---|
| F-1 | Confirmar una `DonationIntent` y aplicar fondos son actos separados. `PENDING → CONFIRMED` no significa fondos aplicados; la aplicación ocurre después |
| F-2 | `confirmDonationIntent()` no crea `Fund`, no aplica fondos ni incrementa el ledger; la génesis del `Fund` y la aplicación financiera pertenecen al flujo posterior, que consume una intención `CONFIRMED` |
| D1 | Barrera idempotente por intención: comando de sistema `APPLY_FUNDS` (clave conceptual `APPLY_FUNDS:{intentId}`, guardada como `_id = {commandType, commandId}`), `commandId = intentId`, que devuelve el resultado original ante duplicados, en el registro de comandos procesados del módulo; nunca el `commandId` del cliente; una sola aplicación efectiva también con reintentos y concurrencia |
| Claves | Identidad `(commandType, commandId)` con espacio separado **solo** para el comando de sistema; entre comandos de cliente se mantiene I1 |
| D2 | Recuperación automática: disparo inmediato y scheduler de respaldo en `app`; la consulta de intenciones recuperables pertenece a `convocatoria`; segura con varias instancias (ADR-043) |
| D3 | Estado terminal **`FUNDING_REJECTED`** (no `FAILED`): solo por rechazo permanente. `CampaignFundingLimitExceededException` es permanente en este corte. `CloseOnTargetCloseNotSupportedException` **no** lleva a `FUNDING_REJECTED` mientras R4 esté pendiente. Conflictos transitorios: reintento. Anomalías de invariante: se propagan |
| D4 | P1 fuera de corte; una financiación rechazada necesita P1 para completar el flujo financiero real; no se habilita dinero real sin él |
| D5 | Confirmación manual solo por `ADMINISTRATOR` de la organización; nunca para intenciones de pasarela; `confirmedBy` = actor real; se reutiliza `ConvocatoriaAuthorizationPolicy` |
| D6 | La aplicación la dispara el sistema; no requiere un segundo acto humano |
| D7 | Especificación (Enmienda 2, ADR-043, matrices y documentos) antes del código |

**P9 — decidido (2026-10-02, opción a):** las intenciones `CONFIRMED` de convocatorias `CLOSE_ON_TARGET + CLOSE` quedan fuera de la cola de recuperación mientras R4 no exista; no pasan a `FUNDING_REJECTED` ni se reintentan indefinidamente; R4 deberá definir cómo vuelven a ser elegibles. Una intención `FUNDING_REJECTED` nunca vuelve a la cola.

**Pendientes:** P8 (contenido del outbox de la génesis, `core`), P10 (trazabilidad de `FUNDING_REJECTED`). Enmienda 2 §7.

### 6.21 Pertenencia documental de ADR-043 — instrucción humana del 2026-10-03

**Instrucción humana explícita del 2026-10-03** (tarea de ordenamiento documental de Convocatoria): "ADR-043 NO pertenece a este bloque como documento propio. Es documentación de otro compañero/bloque."

| Punto | Registro |
|---|---|
| Pertenencia de `ADR-043-recuperacion-aplicacion-fondos-convocatoria.md` | Documento de otro bloque. **No** forma parte de la documentación normativa de Convocatoria |
| Tratamiento en Convocatoria | Solo referencia explícita, como dependencia externa. No se integra en ADR-037. Convocatoria no modifica, mueve, renombra ni consolida ADR-043 |
| Bloque dueño | NO DEFINIDO EN LA DOCUMENTACIÓN ACTUAL (la instrucción no lo nombra) |
| Lo que sigue siendo de Convocatoria | La decisión humana D2 de §6.20, que este documento registra; el código de `convocatoria` que la implementa (consulta `findConfirmedPendingApplication`, `implementation_plan.md` §17.5); la Enmienda 2 de ADR-037 (BORRADOR), que cita ADR-043 como ADR asociado |

**Observaciones al re-auditar contra esta instrucción.** No se resuelven aquí:
- La Enmienda 2 (`:6`) llama a ADR-043 "ADR asociado … exigido por la regla 3.5". Su lista de aprobación (§9) incluye "Aprobación humana de ADR-043". La aprobación de la Enmienda 2 depende, por tanto, de un documento de otro bloque (§0.4, DH-C-1).
- ADR-043 declara haber sido "redactado por el agente el 2026-10-02 a partir de la decisión humana D2 (`convocatoria-resumen.md` §6.20)". D7 (§6.20) pedía "Enmienda 2, ADR-043 … antes del código". Quién aprueba ADR-043 es una cuestión del bloque dueño.
- Las auditorías del 2026-10-03 (`auditoria-inventario-documental-convocatoria.md`, `auditoria-plan-consolidacion-documental-convocatoria.md`, `auditoria-ejecucion-consolidacion-convocatoria.md`) clasificaron ADR-043 como documento DIRECTO del bloque. Se conservan intactas como evidencia; esta sección prevalece sobre esa clasificación.
