# Ficha — Preverificación de organizaciones (Nivel 1)

**Naturaleza:** ficha de contrato HTTP, en el formato de la ficha R-7. **No autoriza código:** el código sigue un plan aparte, que aprueba Manuel (dueño de `identity`) cuando la ficha esté congelada.
**Estado: PROPUESTA.** No está congelada: la congela Carlos, después de que Manuel la revise.
**Fecha:** 2026-11-02 (UTC). **Base verificada:** `develop` `3726414`, con R-7 (#165) y Google (#166) fusionados. Cada ruta:línea es de esa base.
**Origen:** D-2, decisión de Carlos del 2026-11-01: la opción B, solo el Nivel 1. El texto literal está en `decisiones-delegadas-2026-10.md`, nota del 2026-11-01.
**Requiere enmienda o ADR:**
- ampliar `GET /platform/organizations` es aditivo, y basta con esta ficha;
- el resultado guardado (si P6 queda en (b)) es un campo nuevo en el documento de la organización, sin índice;
- **el Nivel 2 (RUES) queda fuera** y necesitaría su propio ADR (regla 3.5), como dice Carlos.

---

## 1. Contexto: hechos verificados

| # | Hecho | Dónde |
|---|---|---|
| H-1 | **Un dígito de verificación incorrecto ya es un rechazo duro.** `Nit` comprueba el algoritmo de la DIAN al construirse → 400 `nitCheckDigit`. Un NIT que no tenga 9 dígitos → 400 `nit` | `identity/src/main/java/identity/domain/model/Nit.java:19-24` |
| H-2 | **Un NIT repetido ya es un rechazo duro.** Hay un índice único parcial `uq_organization_nit` (solo cuando hay `nit`) → 409 `OrganizationNitAlreadyRegistered`. El índice cubre las organizaciones en **todos** los estados, también `REJECTED` | `identity/src/main/java/identity/infrastructure/persistence/mongo/documents/OrganizationDocument.java:12`; `.../mongo/repositories/MongoOrganizationRepositoryAdapter.java:25` |
| H-3 | **`specialTaxRegime` con un tipo que no sea `FOUNDATION` ya es un rechazo duro** → 400, al editar y al crear. Lo contrario, una `FOUNDATION` sin `specialTaxRegime`, **se admite**: el campo es opcional (ficha R-7 §3) | `identity/src/main/java/identity/domain/model/Organization.java:183-184` y `:209-210`; `ficha-R7-perfil-organizacion.md:46` |
| H-4 | **`legalName` y `nit` son opcionales al crear y al editar.** Solo los exige `verify()` → 409 `VerificationDataMissing` (R-7, P2 (b)) | `Organization.java:439-441` |
| H-5 | Una ubicación que no está en el catálogo DIVIPOLA ya es un rechazo duro → 400 `location` | `identity/src/main/java/identity/application/service/OrganizationProfileRules.java:24-33` |
| H-6 | **Al leer de la base, el NIT pasa otra vez por `Nit`.** Una organización guardada no puede tener un dígito incorrecto sin fallar al leerse | `identity/src/main/java/identity/infrastructure/persistence/mongo/mappers/OrganizationMapper.java:125-131` |
| H-7 | **Las organizaciones creadas antes de R-7** no tienen los campos del perfil en su documento: se leen con todos los campos a `null`. La semilla de demostración también crea organizaciones | `OrganizationMapper.java:125-131`; `app/src/main/java/com/traceability/app/bootstrap/DemoSeedRunner.java:85` |
| H-8 | **Crear:** `POST /api/v1/organizations` nace en `PENDING_VERIFICATION`, con el perfil opcional | `app/src/main/java/com/traceability/app/web/organization/OrganizationRegistrationController.java:57`; `Organization.java:140`; `Organization.java:204-210` |
| H-9 | **Editar el perfil no cambia el estado, sea cual sea:** `updateProfile` no mira el estado, salvo el bloqueo de P1 en `VERIFIED`. Una organización en `NEEDS_MORE_INFORMATION` que edita **sigue en `NEEDS_MORE_INFORMATION`**, y una `VERIFIED` o `REJECTED` también puede editar | `Organization.java:177-192`; `identity/src/main/java/identity/application/service/UpdateOrganizationProfileService.java:55-68`; ruta en `.../web/organization/OrganizationProfileController.java:42` |
| H-10 | **Reenviar:** `NEEDS_MORE_INFORMATION → PENDING_VERIFICATION`, y se borra la solicitud | `Organization.java:223-230`; `.../web/organization/OrganizationVerificationResubmitController.java:28` |
| H-11 | **La bandeja:** `GET /api/v1/platform/organizations` lista `PENDING_VERIFICATION` y `NEEDS_MORE_INFORMATION`, por orden de creación, con el perfil completo | `app/src/main/java/com/traceability/app/web/platform/OrganizationVerificationQueueController.java:42-46,59`; `identity/src/main/java/identity/application/service/OrganizationVerificationQueueQuery.java:25-31` |
| H-12 | **La organización ve su estado y su perfil** en `GET /api/v1/organizations/{id}`, solo `REPRESENTATIVE` y `ADMINISTRATOR` | `.../web/organization/OrganizationVerificationStatusController.java:37`; `identity/src/main/java/identity/application/service/OrganizationVerificationStatusQuery.java:23-26` |
| H-13 | **Solo una persona de la plataforma decide:** `verify`, `reject` y `request-information`. `REJECTED` es terminal | `.../web/platform/OrganizationVerificationController.java:40,47,55`; `Organization.java:435-452` |

**Conclusión de los hechos (H-1 a H-3):** las tres comprobaciones que nombra D-2 ya son rechazos duros desde R-7. Una organización guardada **nunca** las fallaría. Por eso la ficha no puede implementarlas tal cual: la etiqueta saldría siempre verde. Es la pregunta **P1**.

**Diferencia con el texto de D-2:** D-2 dice "la organización sigue en PENDING_VERIFICATION". Según H-9, al editar el perfil la organización sigue en **el estado que tuviera**. Se anota aquí, y la ficha solo exige que la preverificación **nunca cambie** `verificationStatus` (§2).

## 2. Lo que no cambia

| Regla | Fuente |
|---|---|
| **La preverificación nunca cambia `verificationStatus`**, ni llama a `verify`, `reject` ni `requestInformation` | D-2 ("Siempre decide una persona") |
| Siguen igual los rechazos de R-7: 400 `nitCheckDigit`, 409 `OrganizationNitAlreadyRegistered`, 400 `specialTaxRegime` y 400 `location` | H-1 a H-5 |
| `verify()` sigue exigiendo `legalName` y `nit` | H-4 |
| Ninguna consulta externa. Sin RUES (Nivel 2) y sin estado `UNAVAILABLE` | D-2 |
| La organización vive en `identity`, fuera del Event Store | ADR-048, ADR-050 D11 |
| `REJECTED` es terminal | Ficha S-18, H-13 |
| `contracts` no cambia | — |

## 3. Qué se comprueba y cuándo

**Momentos** (D-2):
- **al crear** (`POST /organizations`, H-8);
- **al editar el perfil** (`PUT /organizations/{id}/profile`, H-9);
- **al reenviar a revisión** (`POST /organizations/{id}/verification/resubmit`, H-10).

**Qué se comprueba:** depende de P1. Con la recomendación de P1 (a), estas son las reglas [Plan]:

| Motivo [Plan] | Falla si | Por qué aporta |
|---|---|---|
| `LEGAL_NAME_MISSING` | no hay `legalName` | `verify()` respondería 409 `VerificationDataMissing` (H-4). Avisa a la plataforma de que tiene que pedir información |
| `NIT_MISSING` | no hay `nit` | Ídem |

- Las tres comprobaciones de D-2 (dígito, NIT único, régimen coherente) **se cumplen siempre** en una organización guardada (H-1 a H-3). Se conservan como garantía y se documentan, pero no producen un motivo propio.
- **PASSED** = sin motivos. **FAILED** = uno o más motivos.

## 4. Dónde se guarda el resultado

Depende de P6. Con P6 (b), "guardar", que es la lectura literal de D-2:
- en el documento de la organización, un campo `preverification` [Plan] `{result: PASSED|FAILED, reasons: [..], checkedAt}`, sin índice;
- se reescribe en cada uno de los momentos de §3;
- **nunca toca `verificationStatus`**;
- no se audita aparte, porque se deriva del perfil, cuya edición ya se audita (`ORGANIZATION_PROFILE_UPDATED`, R-7).

Con P6 (a), "calcular al leer": no se guarda nada, y el resultado se calcula del perfil cada vez que se lee la bandeja.

## 5. Qué ve la bandeja de la plataforma

- Cada elemento de `GET /api/v1/platform/organizations` (H-11) gana `preverification: {result, reasons}` [Plan]. Es aditivo.
- La web pinta **verde** si es `PASSED` y **rojo** si es `FAILED`, con los motivos.
- No cambian ni el orden ni los filtros: la etiqueta **no prioriza** ni salta la cola (P7).
- La organización lo ve o no según P4. Con la recomendación (a), **no lo ve**.

## 6. Prohibición

- **Ninguna automatización verifica ni rechaza.** No hay programador, ni regla, ni umbral que llame a `verify` o `reject` según el resultado (D-2).
- Motivo, según Carlos: aprobar solo con datos públicos (NIT y razón social) permite suplantar a una fundación real, y un rechazo automático deja atrapada a una organización legítima, porque `REJECTED` es terminal.
- Lo vigila un test de arquitectura (test 6 de §8).

## 7. Preguntas (para Carlos y Manuel)

| # | Pregunta | Opciones | Recomendación |
|---|---|---|---|
| **P1** | Las tres comprobaciones del Nivel 1 **ya son rechazos duros** desde R-7 (H-1 a H-3), así que una organización guardada nunca las falla. ¿Qué mide entonces la etiqueta? | (a) **Completitud de lo que `verify()` necesita:** `legalName` y `nit` presentes (H-4). Las tres de D-2 quedan como garantía. (a+) Como (a), y además ubicación y contacto; pero hoy son opcionales (ficha R-7 §3), así que sería una regla de negocio nueva que tendría que decidir Carlos. (b) Solo las organizaciones anteriores a R-7, sin perfil (H-7); queda cubierto por (a). (c) Las tres de D-2 como "PASSED garantizado": la etiqueta resume y sale siempre verde. (d) Del código: que una `FOUNDATION` sin `specialTaxRegime` sea FAILED; pero hoy el campo es opcional (H-3), así que también sería una regla nueva | **(a)**. Es lo único que aporta a la bandeja: el rojo anticipa el 409 `VerificationDataMissing` y le dice a la persona "pide información" antes de intentar verificar. (c) no aporta nada (siempre verde) y además engaña. (a+) y (d) inventan reglas que no están decididas |
| **P2** | ¿Se recalcula al **verificar**? | (a) No. (b) Sí | **(a)**. `verify()` ya bloquea con 409 si faltan los datos (H-4), y una organización verificada sale de la bandeja (H-11) |
| **P3** | ¿Qué pasa con las organizaciones **ya existentes**, creadas antes de R-7 o sin pasar por los tres momentos (H-7)? | (a) Sin resultado (`preverification` ausente): la web muestra "sin comprobar". (b) Se calcula una vez al arrancar para las que están en la bandeja (migración idempotente). (c) No hace falta, si P6 queda en (a) | **(c)**, con P6 (a). Si P6 queda en (b): **(b)**, porque (a) metería un tercer estado que D-2 no quiere ("UNAVAILABLE no hace falta") |
| **P4** | ¿La organización ve su **propia** etiqueta en `GET /organizations/{id}` (H-12)? | (a) No, solo la plataforma. (b) Sí, con los motivos | **(a)** en este corte: D-2 lo sitúa en la bandeja de la plataforma. El formulario de la web puede marcar `legalName` y `nit` como "necesarios para verificar" sin contrato nuevo. (b) es un campo aditivo, si Carlos lo quiere |
| **P5** | ¿Se calcula también al editar en `NEEDS_MORE_INFORMATION`, `VERIFIED` o `REJECTED` (H-9)? | (a) En cada edición, sea cual sea el estado. (b) Solo en `PENDING_VERIFICATION` | **(a)**, porque es más simple: fuera de la bandeja no se ve. Con P6 (a), la pregunta desaparece |
| **P6** | ¿El resultado **se guarda** o **se calcula al leer**? | (a) Se calcula al leer la bandeja, a partir del perfil, sin campo nuevo. (b) Se guarda en los tres momentos (lectura literal de D-2: "guarda un resultado") | **(a)**. Las reglas son deterministas y baratas (dos campos). Así nunca queda desfasado, cubre a las existentes sin migración (P3) y no añade escritura. Contradice la palabra "guarda" de D-2, y por eso lo decide Carlos. Si vuelve el Nivel 2 (RUES), ese sí tendría que guardarse, con su ADR |
| **P7** | D-2 dice "verde (todo coincide, **revisión rápida**)". Pero el Nivel 1 no comprueba la identidad: con (a), verde solo significa "están los datos" | (a) El texto de la etiqueta dice "Datos completos" / "Faltan datos", y la bandeja explica que la identidad se comprueba a mano (RUES, fuera del sistema). (b) "Coincide" / "Falla", como dice D-2 | **(a)**. Un verde que invita a revisar rápido contradice el motivo de la prohibición (suplantación con datos públicos). Se anota, y lo decide Carlos |

## 8. Tests de la Definición de hecho (con las recomendaciones; rojos primero)

**Unitarios**

1. La preverificación de un perfil con `legalName` y `nit` → `PASSED`, sin motivos. Sin `legalName` → `FAILED [LEGAL_NAME_MISSING]`; sin los dos → los dos motivos; el perfil vacío → `FAILED` con los dos.
2. **Negativa:** calcularla en los cuatro estados no cambia `verificationStatus` ni la solicitud de información.

**Integración HTTP (Testcontainers)**

3. Crear con `legalName` y `nit` → el elemento de la bandeja lleva `PASSED`. Crear sin ellos → `FAILED`, con sus motivos.
4. `PUT /profile` que añade lo que faltaba → la bandeja pasa a `PASSED`. `resubmit` → la organización vuelve a la bandeja con el resultado actual.
5. Una organización existente sin campos de perfil, insertada directamente en Mongo → la bandeja lleva `FAILED`, con los dos motivos (P3/P6).
6. **Negativa, test de arquitectura:** la clase de la preverificación [Plan] no llama a `Organization.verify`, `reject` ni `requestInformation` (`callMethod` de ArchUnit), y no hay ningún `@Scheduled` en ella.
7. **Negativa:** `GET /organizations/{id}` no lleva `preverification` (P4 (a)).
8. **Regresión:** los rechazos de R-7 (400 `nitCheckDigit`, 409 `OrganizationNitAlreadyRegistered`, 400 `specialTaxRegime`) siguen igual; ya los cubren los tests de R-7.

**Común**

9. `GoldenPathHttpIntegrationTest` en verde, y `scripts/ci-local.sh` completo en un clon limpio, con el resumen literal de Surefire.

## 9. Documentos que se actualizan con el PR de código

- `referencia-api-v1.md`: una nota fechada con el campo nuevo de la bandeja.
- `ficha-S18-estado-verificacion-organizacion.md`: una nota de que la preverificación no es una transición.
- `estado-fase6.md`.

## 10. Aviso a la web

- **Bandeja de la plataforma** (`GET /api/v1/platform/organizations`): cada solicitud lleva `preverification.result`.
  - Etiqueta **verde** si es `PASSED`.
  - Etiqueta **roja** si es `FAILED`, con los motivos (`LEGAL_NAME_MISSING`, `NIT_MISSING`) traducidos y la acción sugerida: "Pedir información".
  - El texto de la etiqueta depende de P7.
- **Los botones no cambian:** verificar, rechazar y pedir información siguen siendo de una persona. La etiqueta **no** debe ordenar ni filtrar la cola.
- **Con P4 (a), la organización no ve la etiqueta.** El formulario de perfil puede marcar `legalName` y `nit` como "necesarios para verificar".
- Se planea cuando la ficha esté congelada (D-2).
