# Auditoría de cierre de las decisiones F-1 / F-2 — Convocatoria

**Fecha:** 2026-10-02. **Rama:** `develop`, HEAD `673eda9`; `convocatoria/` sin versionar.
**Naturaleza:** informe temporal de auditoría, solo lectura. No es documentación canónica. No corrige nada.

Taxonomía: **A** código · **B** test/ejecución · **C** git · **D** documental · **E** inferencia · **F** contradicción · **G** no determinable.

**Decisiones auditadas** (decisiones humanas dadas en la conversación del 2026-10-02):
- **F-1:** confirmar una `DonationIntent` y aplicar fondos son actos separados; `PENDING → CONFIRMED` no implica ledger con fondos; la aplicación ocurre después, mediante otro flujo.
- **F-2:** `confirmDonationIntent()` no crea `Fund` ni aplica fondos; la génesis del `Fund` pertenece a un flujo posterior que consume una intención ya `CONFIRMED`.

---

## 1. Alcance

Comprobar si la documentación del proyecto refleja F-1 y F-2 de forma consistente. El objetivo es encontrar lecturas que lleven a un implementador razonable a construir algo distinto según el documento que lea. El código solo se usa para contrastar que las referencias documentales describen el comportamiento real.

## 2. Fuentes inspeccionadas

- **Normativas:** ADR-037 (§2, §2.3, §2.6, §2.6bis, §5, §7); ADR-037 Enmienda 1 (§1, §5.1–§5.4, §6, §8, §10.3); ADR-032 (línea 47); ADR-041 (líneas 45, 75).
- **De plan y estado:** `implementation_plan.md` (cabecera rev. 2.3–2.5, §1.2, §1.3, §4.4, §7.2, §8, §9.2, §9.3, §11, §13.3); `convocatoria-resumen.md` (§2, §6 cabecera línea 91, §6.4, §6.10, §6.17, §6.18, §6.19); `estado-fase6.md` §3bis.
- **Secundarias:** `golden-path.md` (§3, líneas 26–45; §5.3, §5.4); `plan-api-fase6.md` (E9, línea 92); `api-contract-matrix.md` (líneas 41, 104); `contract-wiring-review.md` (línea 32); auditorías C-01.
- **Código:** `DonationIntentService.confirmDonationIntent`, `CampaignFundingLedgerService.applyFundsForIntent`, `DonationIntentStatus`, `ConfirmDonationIntentCommand`, `FundCommandService.clearFundsGenesis` (`core`).
- **Búsquedas:** `CONFIRMED`, `PENDING`, `confirmDonationIntent`, `applyFundsForIntent`, `Fund`, `génesis`/`genesis`, `CLEAR_FUNDS_AS_GENESIS`, `N9`, `F-1`, `F-2`, `DH-[1-5]`, `§6.1[789]`.

## 3. F-1: estado documental

| Pregunta | Resultado | Evidencia |
|---|---|---|
| ¿F-1 está registrada en algún documento? | **No.** Ningún documento recoge la decisión F-1 como tal. Lo más cercano es `convocatoria-resumen.md` §6.19 DH-2 (línea 500): "Mientras `Fund` siga fuera del alcance de este corte, `CONFIRMED` significa exclusivamente que la intención fue confirmada correctamente […]. La aplicación de fondos es una operación posterior y separada". Está **limitada al corte**; F-1 no lo está | D, G |
| ¿Hay documentos que dicen lo contrario? | **Sí.** Enmienda §5.3 (líneas 204–207): la transición es "barrera antes de cualquier efecto financiero sobre el ledger, en la misma transacción". `convocatoria-resumen.md:178` (H-D4.6) y `:328` (§6.10) lo repiten como decisión aprobada. `implementation_plan.md` §7.2 (línea 240): "el orquestador solo toca el ledger si la transición se aplicó en esa misma transacción" | D, F |
| ¿El código cumple F-1? | Sí: ningún código de producción usa `CONFIRMED` como prueba de fondos y la confirmación independiente no toca el ledger | A, B |

## 4. F-2: estado documental

| Pregunta | Resultado | Evidencia |
|---|---|---|
| ¿F-2 está registrada? | **No como tal.** §6.19 DH-1 (línea 499) dice que confirmar "no implica por sí misma que los fondos ya se hayan aplicado", pero no menciona `Fund` ni génesis. DH-2 (línea 500) dice "ni que exista todavía un `Fund`", limitado al corte | D, G |
| ¿Hay documentos que dicen lo contrario? | **Sí.** Enmienda §5.2, N9 (líneas 194 y 331): "La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`". `convocatoria-resumen.md:167` (§6.4): "Confirmar transferencia/efectivo **es** `CLEAR_FUNDS_AS_GENESIS`". `golden-path.md:30-33`: "webhook confirma pago → `clearFundsGenesis` […] + `CampaignFundingLedger` actualizado (misma transacción)" | D, F |
| ¿Algún documento limita lo anterior a este corte? | `implementation_plan.md` §9.3 (línea 301): "No invoca `clearFundsGenesis` ni toca `Fund` (orquestador, fuera de corte)"; §1.3: génesis y efecto sobre `Fund` fuera de corte | D |
| ¿El código cumple F-2? | Sí: `confirmDonationIntent()` solo modifica el documento de esa intención (V10: de 7 colecciones solo cambia `donation_intents`; ledger en 0); ArchUnit impide depender de `core` | A, B |

## 5. Contradicciones encontradas

| # | Fuente A | Fuente B | Contradicción | Clase |
|---|---|---|---|---|
| X-1 | Enmienda §5.3 (líneas 204–207) [DECISIÓN vigente] | F-1 | §5.3 exige que la transición y el efecto financiero vayan en la misma transacción. F-1 los separa y prevé que la aplicación parta de una intención ya `CONFIRMED`, sin barrera definida para ese segundo paso | **F** |
| X-2 | Enmienda §5.2 N9 (línea 194); `convocatoria-resumen.md:167`; `golden-path.md:30-33` | F-2 | Para la confirmación manual, N9 dice que confirmar *es* la génesis; F-2 dice que la génesis la hace después otro flujo. En la pasarela, el `golden-path` es compatible si confirmación y génesis van en la misma transacción; si no, es X-1 | **F** (confirmación manual, flujo futuro) |
| X-3 | `implementation_plan.md` §4.4 (línea 178), §8 (línea 261); §6.19 DH-3 (línea 501); `estado-fase6.md:71`; código `CampaignFundingLedgerService:66-72` | F-1 ("actos separados") | La operación documentada como oficial **confirma y aplica en un solo acto** partiendo de `PENDING`. F-1 dice que son actos separados | **F** |
| X-4 | §6.19 (líneas 511–512), `estado-fase6.md:70`, plan rev. 2.5 (línea 4): "F-1" y "F-2" = **contradicciones** | Decisiones humanas F-1 y F-2 | Mismas etiquetas con significados opuestos. Quien busque "F-1" en el repositorio encuentra una contradicción abierta, no una decisión | **F** (de nombres) |
| X-5 | §6.19 DH-2 (limitada a "mientras `Fund` siga fuera del alcance") | F-1 y F-2 (sin límite de alcance) | Con `Fund` dentro del alcance, la fuente registrada deja de decir nada sobre el significado de `CONFIRMED`, mientras que las decisiones lo fijan de forma permanente | **F** / G |
| X-6 | Plan §9.2 (línea 297) cita §6.17; §7.2 (línea 242) y §4.4 (línea 178) citan §6.18; `estado-fase6.md:79` cita §6.18 | §6.19 (vigente), que sustituye §6.17 y §6.18 | Referencias a secciones sustituidas que no están marcadas como históricas en el lugar donde se citan | Desactualización (D) |

**Precedencia documentada (D, citada, no decidida aquí).** `convocatoria-resumen.md:91`: las decisiones humanas de §6 "no son una enmienda normativa: […] ADR-037 […] siguen vigentes sin cambios hasta que las enmiendas correspondientes se redacten, revisen y aprueben" (regla 3.5). Según esa regla, la Enmienda §5.3 y N9 siguen siendo normativas frente a F-1 y F-2 mientras no haya enmienda.

## 6. Hallazgos F-A / F-B / F-C

### F-A — ¿Hay una barrera para `CONFIRMED → fondos aplicados`?

**No existe.**
- La única barrera definida es la transición `PENDING → CONFIRMED` (Enmienda §5.3; plan §7.2).
- Las otras protecciones documentadas solo protegen al `Fund`, no al ledger: el `commandId` derivado de la intención (§5.3) y el `fundId` estable (ADR-037 §2.6bis).
- Lo admite la propia Enmienda (§5.3 [ESTADO], línea 207): `clearFundsGenesis` con el mismo `commandId` "es un no-op silencioso […]; sin la barrera previa, un reintento podría sumar dos veces al ledger". El código lo confirma: `FundCommandService.java:84-86` vuelve sin avisar (A).
- **Resultado:** contradicción real (X-1) y decisión humana pendiente.

### F-B — ¿Qué significa `applyFundsForIntent()`?

| Interpretación | Documentos que la sostienen | Clase |
|---|---|---|
| 1. "Confirmar + aplicar", partiendo de `PENDING` | Plan §4.4 (178), §8 (261); §6.19 DH-3 (501: "la intención no queda `CONFIRMED` como consecuencia de ese intento"); `estado-fase6.md:71`; Javadoc y código (`:66-72`) | D, A |
| 2. "Aplicar una intención ya `CONFIRMED`" | Ningún documento. Se deduce de F-1 y F-2 ("un flujo posterior que consume una intención ya confirmada") junto con el plan §8 ("operación […] invocada […] por el orquestador") | E |

**Resultado:** opción 3 del encargo, **dos interpretaciones en documentos distintos**, y además el flujo está incompleto (opción 4). Con la interpretación 2, el código actual devuelve `false` y no aplica nada (B: V7c), es decir, no serviría. No elijo ninguna.

### F-C — Documentos que siguen describiendo el flujo antiguo (confirmar = génesis)

| Documento | Afirmación | Clasificación |
|---|---|---|
| Enmienda §5.2 N9 (194, 331) | "La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`" | **Contradicción real** con F-2 para la confirmación manual en el flujo futuro. Es normativa vigente |
| `convocatoria-resumen.md:167` (§6.4) | "Confirmar transferencia/efectivo **es** `CLEAR_FUNDS_AS_GENESIS`" | **Histórica**: propuesta de la ronda del 30 sept, convertida en N9. Mismo efecto |
| ADR-037 §2.6 paso 3 (125) | Webhook válido → `clearFundsGenesis` (§5.3 añade la barrera) | **No contradictoria** si confirmación y génesis van en la misma transacción (pasarela). Si la confirmación va antes, cae en X-1 |
| `golden-path.md:30-33` | "webhook confirma pago → `clearFundsGenesis` […] + ledger (misma transacción)" | Igual que el anterior. Además es un documento descriptivo con errores ya registrados (`convocatoria-resumen.md` §6.9.4) |
| `api-contract-matrix.md:41`, `plan-api-fase6.md:92` (E9) | Webhook: `clearFundsGenesis` + ledger en la misma transacción; no mencionan la transición | Fuera del alcance de esta fase; no contradictorios en sí mismos, pero tampoco dicen si el webhook confirma antes o en la misma transacción |
| `implementation_plan.md` §9.3, §1.3 | `Fund` fuera de corte | Compatible con F-2 en el corte |

## 7. Prueba adversarial del implementador futuro

**Encargo:** "Implementa el flujo que toma una `DonationIntent` `CONFIRMED` y aplica sus fondos", con solo ADR-037, la Enmienda 1, `implementation_plan.md` y `golden-path.md`.

**Esos cuatro documentos no permiten una única interpretación.** Hay al menos tres implementaciones razonables:

1. **"Una intención `CONFIRMED` ya está aplicada: no hacer nada".**
   - Base: Enmienda §5.3 (la transición es la barrera, en la misma transacción que el efecto), plan §7.2 (línea 240) y `golden-path.md:30-33` (confirmación, génesis y ledger en un solo paso).
   - El implementador trata `CONFIRMED` como "barrera ya consumida" y devuelve un no-op. Es lo que hace hoy `applyFundsForIntent()`.
   - Resultado: los fondos de una intención confirmada de forma independiente **no se aplican nunca**.
2. **"Consumir la intención `CONFIRMED` e invocar `clearFundsGenesis` + incremento del ledger".**
   - Base: plan §9.2 (297: "`CONFIRMED` no afirma que los fondos se hayan aplicado") y §7.2 (242: "Una intención ya `CONFIRMED` no recibe fondos por esa vía; la aplicación posterior queda fuera de corte").
   - El implementador usa el `commandId` determinista como idempotencia.
   - Resultado: el `Fund` queda protegido, pero un reintento puede **sumar dos veces al ledger**, el riesgo exacto que describe la Enmienda §5.3 [ESTADO].
3. **"Usar la operación oficial del plan §8, `applyFundsForIntent()`".**
   - Base: plan §8 (261: la operación "invocada […] por el orquestador"; "Desde rev. 2.4 es `applyFundsForIntent`").
   - Con una intención `CONFIRMED` devuelve `false`. Resultado: **no aplica nada y no da error**, porque el plan define ese `false` como "no se aplicó nada en esta llamada".

Hay una cuarta variante para la confirmación manual. Con N9, el implementador puede decidir que la *confirmación* manual debe ejecutarse como `CLEAR_FUNDS_AS_GENESIS`. Eso cambiaría `confirmDonationIntent()` para que haga la génesis, en contra de F-2.

Las tres primeras son incompatibles entre sí y cada una se apoya en texto literal de los cuatro documentos. **Este es el hallazgo principal.**

## 8. Matriz Documento → Regla → Código

| Regla | Documento | Código actual | Interpretación posible | ¿Contradice F-1/F-2? | Evidencia |
|---|---|---|---|---|---|
| Transición = barrera en la misma transacción que el efecto | Enmienda §5.3 (205); plan §7.2 (240); resumen §6.10 (328) | `applyFundsForIntent` exige `PENDING` | `CONFIRMED` = barrera consumida = fondos aplicados | Sí (F-1) | D, A, F |
| Confirmación manual = `CLEAR_FUNDS_AS_GENESIS` | Enmienda N9 (194); resumen §6.4 (167) | `confirmDonationIntent` sin génesis ni autorización | Confirmar debe crear el `Fund` | Sí (F-2, flujo futuro) | D, A, F |
| Webhook → génesis + ledger en la misma transacción | ADR-037 §2.3, §2.6; golden-path 30–33; api-contract-matrix 41 | Sin orquestador | Confirmar y financiar en un solo paso | No, si va en la misma transacción; sí, si la confirmación va antes | D, E |
| `applyFundsForIntent` = confirmar + aplicar | Plan §4.4 (178), §8 (261); resumen §6.19 DH-3 (501); estado:71 | Sí (`:66-72`) | Operación de un solo acto | Sí (F-1: actos separados) | D, A, F |
| `CONFIRMED` no implica fondos | Resumen §6.19 DH-1/DH-2 (499–500, limitadas al corte); plan §9.2 (297) | Ninguna lectura de `CONFIRMED` en `main` | `CONFIRMED` = solo confirmada | No (compatible, pero limitada al corte) | D, A |
| Confirmar no crea `Fund` en el corte | Plan §9.3 (301), §1.3 | V10: solo cambia `donation_intents` | — | No | D, A, B |
| Etiquetas "F-1"/"F-2" | Resumen §6.19 (511–512); estado:70; plan (4) | — | "F-1" = contradicción abierta | Sí (de nombres) | D, F |
| Javadoc de `ConfirmDonationIntentCommand`: "La invocará el orquestador" | Código (comentario) | Operación independiente (DH-1) | Solo el orquestador confirma | Tensión con DH-1 | A, E |

| Documento | Afirma | ¿Compatible con F-1? | ¿Compatible con F-2? | Estado |
|---|---|---|---|---|
| ADR-037 | No define `CONFIRMED`; webhook válido → génesis | Ambiguo (G) | Sí en la pasarela si va en la misma transacción | Vigente; sin definición del estado |
| Enmienda 1 §5.3 | Barrera en la misma transacción | **No** | — | Vigente, normativa |
| Enmienda 1 §5.2 N9 | Confirmación manual = génesis | — | **No** (flujo futuro) | Vigente, normativa |
| `implementation_plan.md` §4.4, §8 | `applyFundsForIntent` confirma + aplica | **No** (actos separados) | Sí | Vigente (rev. 2.4/2.5) |
| `implementation_plan.md` §9.2, §9.3 | `CONFIRMED` ≠ fondos; `Fund` fuera de corte | Sí | Sí | Vigente; cita §6.17 (sustituida) |
| `convocatoria-resumen.md` §6.10, H-D4.6 | Barrera antes del ledger, misma transacción | No | — | Histórica (30 sept), coherente con §5.3 |
| `convocatoria-resumen.md` §6.19 | DH-1–DH-5; "F-1"/"F-2" como contradicciones | Parcial (limitada al corte; DH-3 presupone el acto combinado) | Parcial (no menciona génesis) | Vigente; colisión de nombres |
| `estado-fase6.md` §3bis | Combinación; "F-1/F-2 abiertas"; cita §6.18 | Parcial | Sí | Desactualizado en referencias |
| `golden-path.md` §3 | Confirmación de pago → génesis + ledger | Ambiguo | Ambiguo | Secundario, descriptivo |
| `plan-api-fase6.md` E9 | Orquestador: intención → `clearFundsGenesis` | Ambiguo (no menciona la transición) | Ambiguo | Fuera de fase |

## 9. Respuestas a las preguntas de refutación

| # | Respuesta | Clasificación |
|---|---|---|
| R1 | **Sí, puede.** ADR-037 no define `CONFIRMED` (§2.6 solo lo enumera) y su único flujo es "correlación válida → `clearFundsGenesis`". Deducir que `CONFIRMED` significa pago con fondos es razonable | Hueco documental (E) |
| R2 | **Sí.** §5.3 (misma transacción) y N9 (confirmar = génesis) llevan a una conclusión distinta de F-1 y F-2 | **F**: decisión humana pendiente |
| R3 | **Sí, y es literal**: plan §4.4 y §8, §6.19 DH-3, `estado-fase6.md:71` y el código | **F** con F-1: decisión humana pendiente |
| R4 | **Sí** para la confirmación manual (N9, resumen §6.4) y, de forma ambigua, para la pasarela (`golden-path`). En el corte, el plan §9.3 lo descarta | **F** (flujo futuro): decisión humana pendiente |
| R5 | **No.** No existe estado `FUNDED` (A: `DonationIntentStatus`) ni ningún documento que defina ese momento | Decisión humana pendiente y dependencia futura |
| R6 | **No.** La única barrera es la de la transición (§5.3). El `commandId` determinista protege al `Fund`, no al ledger (§5.3 [ESTADO]) | Decisión humana pendiente |
| R7 | **No** para una intención `CONFIRMED`. DH-3 solo cubre el fallo de `applyFundsForIntent` partiendo de `PENDING` (la intención queda `PENDING`) | Hueco documental y dependencia futura |
| R8 | **Sí** para `applyFundsForIntent` desde `PENDING` (barrera; B). **No** para la aplicación posterior de una intención `CONFIRMED` | Decisión humana pendiente (igual que R6) |
| R9 | **Parcial.** La génesis manual exige `ADMINISTRATOR` (ADR-032:47, N9); el webhook se autentica por firma (ADR-037 §5). No está definido quién autoriza la confirmación independiente (`confirmedBy` es texto libre, A) ni la aplicación posterior como acto separado | Decisión humana pendiente |
| R10 | **No.** Ningún documento contiene F-1 ni F-2. §6.19 es la fuente de DH-1–DH-5 (limitadas al corte) y usa "F-1"/"F-2" para contradicciones | Hueco documental y colisión de nombres |

## 10. Decisiones humanas todavía necesarias

| Decisión | Pregunta exacta | Fuentes implicadas |
|---|---|---|
| H-1 | ¿Qué barrera de idempotencia y atomicidad protege el ledger al aplicar fondos de una intención ya `CONFIRMED`? ¿Se enmienda §5.3? | Enmienda §5.3; plan §7.2; F-1 |
| H-2 | ¿`applyFundsForIntent()` sigue confirmando y aplicando desde `PENDING`, pasa a aplicar solo una intención ya `CONFIRMED`, o conviven dos operaciones? | Plan §4.4, §8; §6.19 DH-3; código; F-1 |
| H-3 | En el flujo con `Fund`, ¿la confirmación manual sigue ejecutándose como `CLEAR_FUNDS_AS_GENESIS` (N9) o es un acto separado de la génesis? ¿Se enmienda N9? | Enmienda §5.2 N9; resumen §6.4; F-2 |
| H-4 | ¿Quién está autorizado a ejecutar `confirmDonationIntent()` como operación independiente? | N8, N9; ADR-032; DH-1 |
| H-5 | ¿F-1 y F-2 sustituyen la limitación "mientras `Fund` siga fuera del alcance" de DH-2? | §6.19 DH-2; F-1, F-2 |
| H-6 | ¿Qué ocurre si falla la aplicación posterior de una intención `CONFIRMED`? ¿En qué estado queda y qué registro se escribe (relación con P1)? | DH-3; Enmienda §3.3; P1 |
| Documental | Registrar F-1 y F-2 en el lugar canónico y renombrar las etiquetas "F-1"/"F-2" de §6.19, `estado-fase6.md` y el plan para evitar la colisión | X-4 |

## 11. Elementos que ya pueden considerarse cerrados

- `confirmDonationIntent()` no crea `Fund` ni aplica fondos en el corte (A, B: V10, ArchUnit; D: plan §9.3; §6.19 DH-1).
- Ningún código usa `CONFIRMED` como prueba de fondos (A).
- Un rechazo del ledger en `applyFundsForIntent` deja la intención `PENDING`, y una transacción externa que capture la excepción no puede confirmarse (A, B; §6.19 DH-3, DH-4).
- Una intención no se aplica dos veces mediante `applyFundsForIntent` desde `PENDING`: secuencial, concurrente, tras reinicio y en la misma transacción externa (B).
- DH-5: la cita a la decisión inexistente se eliminó del código (A).

## 12. Elementos que NO deben implementarse todavía

- Cualquier flujo que aplique fondos a una intención `CONFIRMED` (depende de H-1, H-2 y H-6).
- Cualquier marca o estado de "financiada" (`FUNDED`, `APPLIED`…).
- Cambios en la semántica o la precondición de `applyFundsForIntent()` (H-2).
- La génesis del `Fund` o su orquestación desde `convocatoria` (fuera de corte; H-3).
- Autorización de `confirmDonationIntent()` (H-4).
- El registro de dinero no aceptable (P1).
- Correcciones documentales: deben esperar a H-1–H-6 para no registrar una interpretación del agente.

## 13. Dictamen final

**`ABIERTO — REQUIERE DECISIÓN HUMANA`**

No se cumplen los criterios de cierre 1, 4, 5 y 6, y el 7 solo en parte:
- **Criterio 1:** hay contradicciones reales entre la Enmienda (§5.3, N9), el plan (§4.4, §8) y F-1/F-2 (X-1, X-2, X-3).
- **Criterio 4:** ningún documento dice qué operación consume una intención `CONFIRMED`.
- **Criterio 5:** `applyFundsForIntent()` admite dos interpretaciones (F-B).
- **Criterio 6:** los fallos del segundo paso no tienen semántica definida ni están declarados fuera de alcance como decisión.
- **Criterio 7:** hay referencias a §6.17 y §6.18 sin marcar como históricas donde se citan, y una colisión de nombres (X-4).

El código actual cumple F-1 y F-2. Pero la prueba adversarial muestra que un implementador futuro tiene al menos tres lecturas literales e incompatibles del paso "aplicar fondos de una intención `CONFIRMED`".
