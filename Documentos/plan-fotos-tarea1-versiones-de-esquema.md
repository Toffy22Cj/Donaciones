# Plan — Fotos, Tarea 1: versiones de esquema y `evidenceRef?` en `core` y `api` (2026-11-19)

**Estado: APROBADO — Manuel, 2026-11-18** (respuesta «plan de la Tarea 1 de fotos (#186) y copia del maestro»), con Q-T1-1 (a), Q-T1-2 (a), Q-T1-3 sí y cinco condiciones al PR de código. *(Estado original: PROPUESTO, sin código hasta la aprobación.)* Como toca `core` (contrato de eventos), el PR de código lo revisa **Carlos**. **Nota (2026-11-19): el PR de código (#187) fue revisado y APROBADO — Carlos, 2026-11-19** (comunicado por Manuel; `decisiones-delegadas-2026-10.md`, «Firmas del 2026-11-19»).

**Norma:** `ADR-051-enmienda-2-fotos-de-los-pasos-y-copia-para-el-donante.md` (APROBADA — Manuel, por delegación de Carlos, 2026-11-18), E2-1 (fotos opcionales y vínculo), E2-2 (versiones de esquema) y E2-3 (sin conexión). Es la tarea 1 de su tabla de Implementación; las tareas 2 a 6 llevan su propio plan.

**Base verificada:** `develop` `4a1f1d8` (2026-11-19). Cada ruta:línea es de esa base.

## 0. Alcance

**Dentro:** las tres versiones nuevas de payload, su registro, la escritura con ellas, `evidenceRef?` en los cuatro comandos (`register`, `from-donation`, `dispatch`, `receive`), el gancho de vínculo y los DTO/controlador.

**Fuera (otra tarea, otro plan):** la ruta de subida `POST …/asset-evidence` (tarea 2), la copia censurada (3), el seguimiento público (4), web (5) y móvil (6). **Consecuencia:** en esta tarea ningún cliente puede subir aún una foto de estos pasos; los tests crean la foto directamente en `media_objects` (como ya hace la entrega en sus tests de integración). La función queda **inerte** en producción hasta la tarea 2: sin `evidenceRef`, todo se comporta como hoy (con la versión nueva).

## 1. Hechos verificados

| # | Hecho | Dónde |
|---|---|---|
| H-1 | Hoy se escriben `ASSET_REGISTERED` 4.0, `ASSET_DISPATCHED` 2.0 y `ASSET_RECEIVED` 2.0 | `EventPayloadRegistry.java:33,35,37`; `PhysicalAsset.java:118,180,206` (registro; `206` es el hijo de una división), `:236` (despacho), `:255` (recepción) |
| H-2 | Las clases son `record` sin `evidenceRef`. Añadirlo a ellas cambiaría el hash recalculado de los eventos ya guardados (E2-2) | `payloads/AssetDispatchedV2Payload.java`, `AssetReceivedV2Payload.java`, `AssetRegisteredV4Payload.java` |
| H-3 | El agregado aplica cada versión con un `case` aparte | `PhysicalAsset.java:375` (registro 4.0), `:451` (despacho 2.0), `:465` (recepción 2.0) |
| H-4 | Otros que leen esas versiones con `instanceof` o con listas de clases: si no se amplían, **los eventos nuevos se ignoran en silencio** | `DonationProjectionHandler.java:47-48,255,266,344,350`; `DonationAuditFactsHandler.java:48,194,206`; `AssetProjectionRouting.java:63`; `app/.../activity/OrganizationActivityQuery.java:219,248,251` |
| H-5 | El servicio: `registerPhysicalAsset` (`:233`), `registerPhysicalAssetFromDonation` (`:354` y `:375`), `dispatchAsset` (`:151`), `receiveAsset` (`:158`). Despachar y recibir pasan `NO_HOOK` (`:154`, `:161`, definido en `:165`) | `core/.../application/command/PhysicalAssetCommandService.java` |
| H-6 | El patrón de la entrega: formato `^sha256:[0-9a-f]{64}$` comprobado **antes** de reclamar el `Command-Id` (`EVIDENCE_REF`, `:57`; `deliverAsset`, `:135`), vínculo en el gancho tras autorizar y antes de `appendAndOutbox`, solo si el agregado generó evento (`transition`) | `PhysicalAssetCommandService.java:135-148` y `transition` |
| H-7 | **El puerto de vínculo solo sirve a la entrega:** `DeliveryEvidencePort.bindToAsset(organizationId, evidenceRef, assetId)` no recibe el paso y `MediaObjectStore.bindEvidence` busca `(organizationId, DELIVERY_EVIDENCE, sha256)` | `contracts/.../media/DeliveryEvidencePort.java`; `app/.../media/DeliveryEvidenceBinding.java`; `MediaObjectStore.java:41` |
| H-8 | `registerPhysicalAsset` calcula el `assetId` de `(organizationRef, commandId)`; un reenvío del mismo comando devuelve antes de validar nada | `PhysicalAssetCommandService.java:233` y siguientes (`AssetIds.of`) |
| H-9 | Los controladores: `/register` (`PhysicalAssetController.java:60`), `/from-donation` (`:84`), `/{assetRef}/dispatch` (`:124`), `/{assetRef}/receive` (`:133`); la entrega ya exige `evidenceRef` (`:145-155`). DTO en `PhysicalAssetDtos.java` (`DispatchRequest` solo lleva `carrierRef`) | `api/.../asset/` |
| H-10 | Precedente de compatibilidad de hash al subir versión | `app/src/test/.../ZoneEventHashCompatibilityTest.java` (test 1: el hash de los eventos viejos no cambia; test 2: lo nuevo entra en el hash; cadena mezclada verde; zona alterada = `HASH_MISMATCH`) |
| H-11 | 16 ficheros de test y de código llaman a `registerPhysicalAsset`, `registerPhysicalAssetFromDonation`, `dispatchAsset` o `receiveAsset` (incluida la semilla `DemoHistoricSeeder`) | `grep` sobre `develop` |

## 2. Diseño

### 2.1 Versiones nuevas (E2-2)

| Evento | Clase nueva (`record`) | Campos |
|---|---|---|
| `ASSET_REGISTERED` **5.0** | `AssetRegisteredV5Payload` | los de `AssetRegisteredV4Payload` + `String evidenceRef` |
| `ASSET_DISPATCHED` **3.0** | `AssetDispatchedV3Payload` | los de `…V2Payload` + `String evidenceRef` |
| `ASSET_RECEIVED` **3.0** | `AssetReceivedV3Payload` | los de `…V2Payload` + `String evidenceRef` |

- Se registran en `EventPayloadRegistry` junto a las anteriores. **Las clases 4.0 y 2.0 no se tocan.**
- `PhysicalAsset` escribe **siempre la versión nueva**, con o sin foto (`evidenceRef` = `null` sin foto), también en el hijo de una división (`registerSplitChild`, `:206`), que nace **sin foto** (E2-2). Los `case` de `apply` ganan una rama por clase nueva.
- Los lectores de H-4 se amplían para aceptar **ambas** versiones. Es la parte que más se olvida y la que más daño haría en silencio (un lote despachado que no aparece en el seguimiento): lleva su propio test (§4, grupo C).
- `getFinalEvidenceRef()` (`PhysicalAsset.java:583`) **no cambia**: sigue siendo la foto de la entrega.

### 2.2 `evidenceRef?` en los comandos (E2-1)

- `PhysicalAsset.register`, `registerFromDonation` (y la variante con campaña), `dispatch` y `receive` ganan un parámetro `evidenceRef` (nullable). La entrega no cambia.
- `PhysicalAssetCommandService`: los cuatro métodos ganan `evidenceRef`. **Orden** (el de F3, E2-1): formato (si no es `null`, tiene que cumplir `EVIDENCE_REF`, y falla con `InvalidEvidenceRefException` → 400 **antes** de reclamar el `Command-Id`, igual que `deliverAsset`) → cargar → autorizar → validar en el agregado → **vincular solo si hay `evidenceRef`** y el agregado generó evento → `appendAndOutbox`. Un rechazo por permisos o por estado **no vincula** la foto. Sin `evidenceRef`, el gancho no hace nada, como hoy.
- En el registro, el vínculo usa el `assetId` calculado de `(organizationRef, commandId)` (H-8). Un reenvío del mismo comando devuelve antes de vincular.
- **Compatibilidad de firmas:** se mantienen las firmas actuales como sobrecarga que pasa `null` (el patrón que ya usa el servicio, `:354` y `:375`), para no tocar los 16 llamadores de H-11 salvo donde convenga. La semilla `DemoHistoricSeeder` no cambia.

### 2.3 El gancho de vínculo con el paso (H-7)

`DeliveryEvidencePort` no sabe de pasos. Propuesta mínima: **añadir un método** al puerto, sin tocar el de la entrega:

```java
void bindToAsset(String organizationId, String evidenceRef, String assetId, EvidenceStep step);
```

con `EvidenceStep { REGISTERED, DISPATCHED, RECEIVED }`, que `DeliveryEvidenceBinding` traduce al propósito `REGISTRATION_EVIDENCE`, `DISPATCH_EVIDENCE` o `RECEIPT_EVIDENCE`. Esto exige que la tarea toque también **`contracts`** y **la implementación en `app`** (`DeliveryEvidenceBinding`, `MediaObjectStore.bindEvidence` con un parámetro de propósito, y `MediaPurpose` con los tres valores nuevos, que la tabla de ADR-051 Enm. 2 asigna a la tarea 2). **Esto amplía los módulos de la tarea 1 (`core`, `api`) con `contracts` y un trozo de `app`:** ver Q-T1-1.

## 3. Preguntas para Manuel (cada una con mi recomendación)

- **Q-T1-1 — Módulos.** La tabla de ADR-051 Enm. 2 dice «`core`, `api`», pero el vínculo necesita el paso (H-7), y eso toca `contracts` y `app`. (a) **Recomendada:** el PR de la tarea 1 incluye el método nuevo del puerto, su implementación y los tres valores de `MediaPurpose`; la tarea 2 solo añade la ruta de subida. (b) La tarea 1 deja el puerto con un doble de test y el vínculo real entra en la tarea 2: se fusionaría un gancho que en producción no vincula nada. **Recomiendo (a).**
- **Q-T1-2 — «La misma foto en otro paso».** ADR-051 Enm. 2 E2-1 dice que la misma foto en otro **paso** da `409 EvidenceAlreadyBound`, y también que una foto «de otro propósito» da `400 EvidenceNotFound`. Con propósitos distintos por paso, una foto subida como despacho **no existe** para la recepción: lo que el diseño produce es `400`, no `409`. (a) **Recomendada:** `409` solo para «la misma foto, otro activo»; «otro paso» es `400` (la foto no es de ese propósito), y el texto de la enmienda se corrige en una nota fechada. (b) Hacer que `409` valga también para otro paso: exige que el vínculo mire todos los propósitos, más código y una consulta más por petición. **Recomiendo (a).**
- **Q-T1-3 — Otros caminos de registro.** *(Aclaración, 2026-11-19, pedida por Manuel: la frase «no hay forma de añadirla después sin otra versión de esquema» de más abajo era **incorrecta**. El hijo se registra con `ASSET_REGISTERED` 5.0, así que el campo `evidenceRef` ya existe, con `null`. Para dar foto al hijo más adelante no hace falta otra versión de esquema; lo que lo impide hoy es que el hijo lo registra la saga de división, que es un flujo interno sin petición de cliente que pueda llevar una foto, y que `registerSplitChild` no recibe ningún `evidenceRef`. Habría que cambiar el flujo, no el esquema.)* El hijo de una división nace **sin foto** (E2-2). ¿Se acepta que la saga de división nunca lleve `evidenceRef` en este corte? **Recomiendo sí** (es lo que dice E2-2); lo dejo a tu confirmación porque no hay forma de añadirla después sin otra versión de esquema.

## 4. Tests (rojos primero)

Cada grupo se escribe antes y se ve fallar por la razón correcta.

**A. Compatibilidad de hash (el riesgo principal; patrón de H-10).**
1. El hash recalculado de eventos 4.0 / 2.0 / 2.0 guardados es **idéntico** antes y después (`ZoneEventHashCompatibilityTest` ampliado, o clase hermana).
2. Un evento nuevo 5.0 / 3.0 / 3.0 con foto lleva `evidenceRef` **dentro** del hash; alterarlo → `HASH_MISMATCH`.
3. Una cadena mezclada (eventos viejos y nuevos de un mismo activo) pasa la verificación de integridad en verde.
4. Un evento nuevo **sin** foto lleva `"evidenceRef": null` en el mapa canónico y su hash se recalcula igual al volver a leerlo.

**B. Comandos (`core`, con Mongo real).**
5. `register`, `from-donation`, `dispatch` y `receive` **sin** `evidenceRef`: se escribe la versión nueva con `null`; el estado del activo es el mismo que con la versión vieja (mutación: si el `case` nuevo de `apply` se olvida, falla).
6. Con `evidenceRef` válido y existente: se vincula al activo y queda en el evento.
7. Formato inválido (`"abc"`, mayúsculas, 63 hex) → `InvalidEvidenceRefException` **sin reclamar el `Command-Id`** ni leer el activo (aserción negativa: el mismo `Command-Id` se puede reutilizar después con un cuerpo válido).
8. Foto inexistente, de otra organización, borrada o de otro propósito → `EvidenceNotFoundException` y **ningún evento** escrito.
9. La misma foto en **otro activo** → `EvidenceAlreadyBoundException` y ningún evento (Q-T1-2).
10. **Un rechazo por permisos o por estado no vincula la foto:** empleado de otra organización; despachar un lote ya despachado → la foto sigue libre y se puede usar después.
11. Reintento del mismo comando con el mismo `Command-Id` y el mismo `evidenceRef` → no-op sin error ni segundo vínculo (E2-3, fallo ambiguo). Mismo `Command-Id` con otro `evidenceRef` → se trata como el comando ya procesado (el resultado guardado es del primero).
12. El hijo de una división nace con versión 5.0 y `evidenceRef` `null`.

**C. Lectores (donde un olvido es silencioso).**
13. Un activo registrado, despachado y recibido con las **versiones nuevas** aparece igual en: la proyección de donación (`DonationProjectionHandler`), los hechos de auditoría (`DonationAuditFactsHandler`), el enrutado de proyecciones (`AssetProjectionRouting`), la actividad de la organización (`OrganizationActivityQuery`) y el historial público. Se compara contra el mismo recorrido con las versiones viejas: **resultados idénticos**.
14. Mutación: quitar el `instanceof` de una versión nueva en cada uno de esos cinco sitios hace fallar el test 13.

**D. `api`.**
15. Los cuatro endpoints aceptan `evidenceRef` opcional; ausente → igual que hoy; presente con formato inválido → 400 con el cuerpo estándar; foto inexistente → 400 `EvidenceNotFound`; otro activo → 409 `EvidenceAlreadyBound`. El `403` uniforme (DD-01) se mantiene y no vincula.
16. Inventario de rutas y contrato HTTP: sin rutas nuevas en esta tarea.

## 5. Verificación y entrega

- Rama `feat/core-fotos-versiones-de-esquema` desde `develop`; PR hacia `develop`; **no se fusiona sin tu aprobación ni la revisión de Carlos**.
- `scripts/ci-local.sh` **completo** adjunto en el PR (salida literal de Surefire por módulo, `core`, `contracts`, `api` y `app`), y `mvn test` de cada módulo tocado completo, no solo las clases nuevas.
- Mutaciones: las del grupo C (test 14) y una más: si el vínculo se mueve **antes** de autorizar, el test 10 falla.
- Toda cifra de tests lleva su procedencia (comando, commit, salida).

## 6. Riesgos

1. **Lectores que ignoran la versión nueva en silencio** (H-4): lo cubre el grupo C. Es el mayor riesgo de la tarea.
2. **Hash de eventos antiguos:** lo cubre el grupo A; las clases viejas no se tocan.
3. **Alcance mayor que el de la tabla** (Q-T1-1): el PR toca `contracts` y `app`. Si Manuel elige (b), el gancho queda sin implementación real hasta la tarea 2.
4. **Inercia hasta la tarea 2:** sin ruta de subida, la función no se puede usar de punta a punta; los tests de integración preparan la foto a mano. Se dice en el PR.
5. **Semilla y escenarios de demostración:** no cambian (no mandan foto). Las capturas y la evidencia ya publicadas no se tocan.

## 7. Qué no cambia

El contrato público (ADR-021-D) y el seguimiento: la Enmienda 2 de ADR-021-D lo cambia en la tarea 4, que sigue **bloqueada hasta ADR-053 u otro límite de peticiones aprobado**. Las tareas 1 a 3 no dependen de eso.
