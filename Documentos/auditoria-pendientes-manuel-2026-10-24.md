# Auditoría — Pendientes de Manuel (backend), 2026-10-24

**Naturaleza:** auditoría de solo lectura. No decide, no aprueba y no cambia ninguna norma.
**Fecha:** 2026-10-24 (UTC). **Autor:** Manuel, con asistencia de Claude.
**Base verificada:** `origin/develop` en **`0b3dc3e`** (`Merge pull request #112`). `paxfide-web` (repositorio PaxFide) `origin/develop` en `bd099a3`, solo para leer `plan-fase7-pantallas.md`.
**Criterio de aprobación usado:** solo cuenta como aprobación un texto de Carlos ("plan aprobado", una revisión del PR o un comentario) que se pueda citar con su enlace o su commit. Si no se encuentra, la fila dice "sin aprobación citable".

---

## 0. Documentos revisados y dónde están

| Documento | Dónde está | Observación |
|---|---|---|
| `ADR-050-cuenta-y-sesiones.md` | `develop`, `Documentos/` | APROBADO — Carlos, 2026-10-18 (`:5`). Tareas en la tabla `:287-293` |
| `ADR-051-fotos-y-almacenamiento.md` | `develop`, `Documentos/` | APROBADO — Carlos, 2026-10-18 (`:5`). Tareas en `:295-301` |
| `ficha-R6-datos-estilo-v2.md` | `develop`, `Documentos/` | CONGELADA (`:4`). Bloques A a D en `:14-24` |
| `plan-fase7-pantallas.md` | **repositorio PaxFide**, `develop` `bd099a3`, `Documentos/` | No está en Donaciones. §3 en `:70-84` |
| `estado-fase6.md` | `develop`, `Documentos/` | Entradas recientes §0.30 a §0.35 (`:181-279`) |
| `plan-r3-r2-revocacion-y-cambio-de-contrasena.md` | **fuera de Git**: `~/Escritorio/` (modificado el 2026-10-20 22:12) | **No está en ninguna rama** de Donaciones ni de PaxFide. Estado PROPUESTO (`:3`) |
| PR abiertos | GitHub | Solo #113 y #114. Ninguno tiene revisiones ni comentarios |
| Ramas remotas | `git branch -r` tras `git fetch --prune` | §5 |

**Colisión de nombres:** "R-6" significa dos cosas distintas. En ADR-050 es la tarea de Google (`ADR-050:177`, `:293`). En la ficha es "datos que piden las pantallas del estilo v2". En este documento, la primera se llama **ADR-050 T5 (Google)** y la segunda **ficha R-6**.

---

## 1. Tabla de pendientes de Manuel

Tamaño: **S** = hasta medio día; **M** = de 1 a 2 días; **L** = 3 días o más. Incluye tests en rojo, verde por capas, mutaciones, documentos y `ci-local.sh`.

| # | Fuente (archivo y línea) | Estado comprobado en el código (ruta y línea) | Qué falta exactamente | Quién lo desbloquea | Tamaño |
|---|---|---|---|---|---|
| **M-1** | `ficha-R6-datos-estilo-v2.md:19-20` (Bloque B: G-2, G-6); PR #113 | Implementado en `feat/r6-bloque-b` (`9ba02a2`), **no en `develop`**: `develop` no tiene `FundClearingDatesReadPort` ni la ruta `/funds/monthly` | La revisión y la fusión del #113. El plan del Bloque B lo aprobó **Manuel** (`5b8e30f`, "aprobación de Manuel (2026-10-23)"); **no hay texto de Carlos citable** que lo apruebe (§4, H-1) | Carlos | — (hecho; solo revisión) |
| **M-2** | PR #114; corrección de Manuel del 2026-10-24 (plan C §2.2 y P-R6C-3) | `AssetHistoryProjectionDocument.java:39` (`private long sequence;`). El génesis tiene **`sequence` 1**, no 0: `MongoEventStoreAdapter.java:45` y `:63` (`newSequence = currentSequence + 1` desde `expectedVersion == 0`), `EventStreamGenesisReadPort.java:17` (`FIRST_SEQUENCE = 1`) | La premisa de la corrección ("choca con la sequence 0 del génesis") **contradice el código** (§4, C-1). Falta que Manuel elija: (a) `Long` nulo, test y mutación como medida defensiva, con el plan corregido sobre el génesis; (b) dejar P-R6C-3 como está, con una nota; (c) otra cosa | Manuel | S |
| **M-3** | PR #114; corrección de Manuel del 2026-10-24 (plan C, P-R6C-1) | El plan C hoy propone `Map<Long, String>` (`plan-r6-bloque-c.md:79`) | El puerto devuelve un `enum` de cuatro valores declarado en `contracts`, y el test 9c lo recorre | nadie | S |
| **M-4** | PR #114; corrección de Manuel del 2026-10-24 (plan D §2.2) | El plan D no dice de dónde salen los lotes de `BATCH_ANCHORED` (`plan-r6-bloque-d.md:80`) | Escribir que salen de la agregación por `merkleBatchId` de G-7 y después de `findByBatchId` (`MerkleBatchRepositoryPort.java:12`), nunca consultando `coverage` | nadie | S |
| **M-5** | PR #114; corrección de Manuel del 2026-10-24 (plan D §6.2) | Una convocatoria cerrada se sabe hoy por `ConvocatoriaStatus.CLOSED` (`ConvocatoriaStatus.java:8`), escrito por `Convocatoria.close` (`Convocatoria.java:144`) y `closeIfOpen` (`ConvocatoriaRepositoryPort.java:26`), persistido en `ConvocatoriaDocument.status` (`ConvocatoriaDocument.java:29`). **La fecha del cierre no está en la convocatoria**: sale de la entrada `CONVOCATORIA_CLOSED` del registro de auditoría (`CampaignTimelineQuery.java:30-31`). `findByOrganizationRef` (`ConvocatoriaRepositoryPort.java:29`) no filtra por estado | Escribir las propuestas de Manuel, marcadas "propuesta de Manuel, pendiente de Carlos": P-R6D-1 opción (b), sin `subject` y con `eventCount`; P-R6D-2 opción (a), limitada a las convocatorias `CLOSED` de la organización, con las rutas y líneas de esta fila | nadie (escribir); Carlos (decidir) | S |
| **M-6** | `ficha-R6-datos-estilo-v2.md:21-22` (Bloque C); `plan-r6-bloque-c.md:3` | Nada de G-3 ni G-4 en `develop`: `LogisticsReadItem` sin `parentAssetId`; `AssetTransitionReadModel` sin `sequence` | "Plan aprobado" de Carlos (citable), la fusión del #113 (comparten archivos, plan C §0.1) y después la implementación | Carlos | M |
| **M-7** | `ficha-R6-datos-estilo-v2.md:23-24` (Bloque D); `plan-r6-bloque-d.md:3` | No existen `GET /organizations/{id}/integrity` ni `/activity` | Respuestas de Carlos a P-R6D-1 y P-R6D-2 (ficha CONGELADA); "plan aprobado"; fusión de B y C; implementación | Carlos | L |
| **M-8** | `ADR-050:289` (tarea 1, R-3 + R-2); plan en `~/Escritorio/plan-r3-r2-…md` | Nada implementado: no hay `credentialsNotBefore`, `resolveAuthenticatedPrincipal` ni `POST /account/password` en `develop` (búsqueda sin resultados). `JwtAuthFilter.java:97` sigue llamando a `resolvePrincipal` | Llevar el plan al repositorio; cambiar el dueño a Manuel (el plan dice "Dueño: Carlos", `:7`); base nueva `0b3dc3e`; hechos reverificados (§2); "plan aprobado" de Carlos; implementación | Carlos | L |
| **M-9** | `ADR-050:290` (tarea 2, R-4: recuperación) | No hay `POST /auth/password-reset`, `password_reset_tokens` ni `PasswordResetMailPort` (búsqueda sin resultados) | Plan nuevo (D8, Q5, tests 11-18); aprobación; implementación. Depende de M-8, que pone `credentialsNotBefore` (D8 lo fija al confirmar) | Carlos | L |
| **M-10** | `ADR-050:291` (tarea 3, R-5 y D1); `plan-fase7-pantallas.md:84` (`GET /account/profile` ⛔) | `RegisterRequest(String email, String password)` (`AccountRegistrationController.java:28`), sin aceptaciones; `ACCOUNT_CREATED` guarda el correo: `Map.of("email", email.value(), …)` (`CreateAccountService.java:59`); `Account` sin nombre ni aceptaciones (`Account.java:12-17`) | Plan nuevo (D1, D2, D3, D10, tests 19-23); aprobación; implementación. Necesita las **versiones vigentes** de términos, tratamiento de datos y términos de organización (D2, `ADR-050:72`), que dependen de quien redacte los textos (M-33, `ADR-050:200`) | Carlos (y quien redacte M-33) | L |
| **M-11** | `ADR-050:292` (tarea 4, D6 y D7) | `DeactivateAccountService.deactivateAccount` (`:32`) y `ReactivateAccountService.reactivateAccount` (`:32`) existen sin ruta HTTP; no hay `REACTIVATE_ACCOUNT` en `contracts` | Plan nuevo (D6, D7, tests 24-26); aprobación; implementación. Depende de M-8 (desactivar fija `credentialsNotBefore`) | Carlos | M |
| **M-12** | `ADR-050:293` (tarea 5, Google, "última prioridad", `:177`) | Ninguna referencia a Google en el código de `develop` | Plan, marcado "pendiente de que Carlos confirme si entra en este corte". Necesita los ID de cliente de web y móvil (`GOOGLE_OAUTH_CLIENT_IDS`, `ADR-050:175`) | Carlos (decidir si entra); Adonis (ID del móvil) | M |
| **M-13** | `ADR-051:300` (tarea 4, evidencia de entrega: "Manuel con Adonis") | `evidenceRef` solo se exige no vacío (`PhysicalAssetController.java:151`); `PhysicalAssetCommandService.java:100-103` no valida el formato `sha256:` ni vincula; no hay `DeliveryEvidencePort` | Plan nuevo (F3, F8, tests 6-11 de ADR-051); aprobación; implementación. El lado del móvil necesita la enmienda de ADR-043 (Adonis, `ADR-051:254`) | Carlos (plan); Adonis (móvil, en paralelo) | L |
| **M-14** | `ADR-051:301` (tarea 5: lectura de evidencia, retirada y huella en el seguimiento) | No hay `READ_DELIVERY_EVIDENCE` ni `REMOVE_MEDIA` en `contracts`; no existe la enmienda de ADR-021-D (búsqueda en `Documentos/` sin resultados) | La enmienda de ADR-021-D, que redacta Carlos antes del código (`ADR-051:133`, `:255`); M-13 fusionada; plan; aprobación; implementación | Carlos | M |
| **M-15** | `estado-fase6.md:268` (CORS sin `PUT` ni `DELETE`, DD-57) | `CorsConfig.ALLOWED_METHODS = List.of("GET", "POST", "OPTIONS")` (`CorsConfig.java:34`). Desde el #111 hay `@PutMapping` y `@DeleteMapping` en `OrganizationLogoController.java:45` y `:56`, que un navegador de otro origen no puede usar | El "sí" de Carlos para cambiar DD-57 (RATIFICADA, `decisiones-delegadas-2026-10.md:98`) | Carlos | S |
| **M-16** | `ADR-037-enmienda-5-…md:5` (`[Falta: hora exacta y lugar …]`); `estado-fase6.md:235`, `:254`; `plan-a2-historial-donaciones.md:6` | Marca abierta en los tres archivos | Cerrar la marca con lo que Manuel dio el 2026-10-24: 2026-10-22, 00:12 hora de Colombia (05:12Z), por WhatsApp. Es un dato de Manuel, **sin enlace ni commit de Carlos que se pueda citar** (§4, H-3) | nadie | S |
| **M-17** | `estado-fase6.md:253` (nota "A2 implementado en <commit>" en la ficha L-FRONTS) | `ficha-lecturas-fronts-M07-M15-M26.md` no tiene la nota (búsqueda sin resultados). A2 está en `develop` desde `0b3dc3e` (#112) | Añadir la nota fechada | nadie | S |
| **M-18** | `estado-fase6.md:252`, `:268`, `:279` (avisos a la web y al móvil de A2, del logo y del Bloque A) | Fuera de este repositorio | Los avisos, con el commit de `develop` | nadie (fuera del repositorio) | S |
| **M-19** | `estado-fase6.md:229` (DT-1) y `plan-adr051-tarea1-almacenamiento.md:371` (R-1: memoria al decodificar 40 MP) | `S3MediaStorageAdapter.isAmbiguous` (`:104`) | Deuda registrada, sin tarea en este corte. R-1 se mide antes de un despliegue real, que sigue sin decidir (`ADR-051:51`) | Carlos (despliegue) | S |

---

## 2. Plan R-3 + R-2 (base `4bef7be`) frente a `develop` `0b3dc3e`

Se comprobó cada "hecho verificado" de su §2 en los dos commits.

| Hecho del plan (§2) | En `4bef7be` | En `0b3dc3e` | ¿Cambió? |
|---|---|---|---|
| `JwtTokenVerifier` no lee `iat`; `JwtVerification` solo lleva `subject`; `NimbusTokenIssuer` siempre escribe `iat` | Sí (`JwtVerification.java:7`; `NimbusTokenIssuer.java:49`) | Igual | No |
| `JwtAuthFilter` llama a `resolvePrincipal(sub)`; `core` lo llama 5 veces y `convocatoria` 3 | `JwtAuthFilter.java:97`; 5 y 3 en `src/main` | Igual (también en `feat/r6-bloque-b`) | No |
| Implementaciones de `IdentityPrincipalPort`: la real y dos dobles; ninguna lambda. `TokenIssuerPort`: solo `NimbusTokenIssuer` | Sí | Igual | No |
| `ChangeCredentialsService.changeCredentials(actor, accountId, nueva)` sin ruta HTTP ni comprobación de la actual; 8 llamadas en `AccountApplicationServiceIntegrationTest` | `ChangeCredentialsService.java:37`; 8 | Igual | No |
| `Account.changeCredentials` y `Account.deactivate`: 1 llamada de producción cada uno; 2 y 8 de test | 1 y 1; 2 y 8 | Igual | No |
| `Account.reconstitute(...)` (6 argumentos): el mapeador y **10 clases de test** | 10 | **11**: se añadió `OrganizationLogoReferenceIntegrationTest` (#111) | **Sí** |
| Los servicios de `identity` usan `ObjectProvider<Clock>`; `ChangeCredentialsService` y `DeactivateAccountService` usan `Instant.now()` | `ChangeCredentialsService.java:52`; `DeactivateAccountService.java:45` | Igual | No |
| `NimbusTokenIssuer` y `JwtTokenVerifier` son `@Component` con `Clock.systemUTC()` en el constructor público; el test de invitaciones ya controla el reloj | `NimbusTokenIssuer.java:24`, `:32`; `JwtTokenVerifier.java:35`, `:46` | Igual | No |
| §7 Entorno: "Maven no resuelve dependencias (proxy); la evidencia la ejecuta el humano" | — | Hoy `mvn` y `scripts/ci-local.sh` corren en la sesión del agente (evidencias de #110 a #113 en `Documentos/evidencia-ci/`) | **Sí** |
| Cabecera: "Dueño: Carlos" (`:7`) | — | Manuel indica el 2026-10-24 que el dueño es Manuel. No hay texto de Carlos citable sobre el cambio | **Sí** (H-4) |

**Desde `4bef7be`,** el diff de `identity/src/main`, `api/.../auth` y `contracts/src/main` solo trae el logo, `AccountEmailQuery`, el estado de verificación, `MediaStoragePort` y `CampaignPublicSummaryPort`. **Nada toca** el filtro JWT, los dos puertos ni `ChangeCredentialsService`.

---

## 3. Lo que no es de Manuel pero lo bloquea

| # | Qué | Fuente | De quién | Bloquea |
|---|---|---|---|---|
| B-1 | Revisar y fusionar #113 y #114 | GitHub | Carlos | M-1, M-6, M-7 |
| B-2 | Responder P-R6D-1 y P-R6D-2 (ficha CONGELADA) | `plan-r6-bloque-d.md:171-172` | Carlos | M-7 |
| B-3 | "Plan aprobado" citable de los planes C, D, R-3 + R-2 y los siguientes | Regla 3.4 | Carlos | M-6 a M-14 |
| B-4 | Confirmar si Google entra en este corte | `ADR-050:177` | Carlos | M-12 |
| B-5 | Redactar la enmienda de ADR-021-D | `ADR-051:133`, `:255` | Carlos | M-14 |
| B-6 | Decidir CORS `PUT`/`DELETE` (DD-57) | `estado-fase6.md:268` | Carlos | M-15 |
| B-7 | Enmienda de ADR-043 para el móvil (entregar con conexión, foto antes de entregar) | `ADR-051:254` | Adonis | El lado móvil de M-13 (no el backend) |
| B-8 | Textos y versiones de términos, tratamiento de datos y términos de organización | `ADR-050:72`, `:200` (M-33) | Quien redacte M-33 (no lo dice el ADR) | M-10 |
| B-9 | ADR-051 Tarea 3, portada (`coverImageUrl`, ⛔ en `plan-fase7-pantallas.md:84`) | `ADR-051:299` | Carlos | Ninguna tarea de Manuel; sí al front |
| B-10 | Búsqueda y orden del descubrimiento, R-1 (⛔ en `plan-fase7-pantallas.md:84`) | Ficha y plan en `~/Escritorio/`, **fuera de Git**; la ficha dice "tarea R-1 del backend de Carlos" (`:7`) | Carlos | Ninguna tarea de Manuel; sí al front |
| B-11 | ID de cliente de Google del móvil | `ADR-050:173-175` | Adonis | M-12 |

---

## 4. Hallazgos y contradicciones (anotados, no resueltos)

| # | Hallazgo | Evidencia |
|---|---|---|
| **C-1** | La corrección del plan C ("la sequence 0 … choca con la sequence 0 del génesis") **contradice el código**: el primer evento de un stream tiene `sequence` 1. Un paso leído como 0 no coincide con ningún evento del mapa y sale `PENDING`, que es lo que ya dice el plan. Lo que sí es cierto es que un `long` primitivo no distingue "sin campo" de "0". `AssetTransition.sequence` existe desde el primer commit (`4ab3a34`) y el handler lo escribe siempre (`DonationProjectionHandler.java:307`) | `MongoEventStoreAdapter.java:45`, `:63`; `EventStreamGenesisReadPort.java:17`; `EventBatchMembershipPort.java:12`; `AssetHistoryProjectionDocument.java:39` |
| **H-1** | **Ningún PR del #104 al #112 tiene revisiones ni comentarios en GitHub.** Del #104 al #112, `buelvasmanuel` fusionó #104, #105, #108, #109, #110, #111 y #112; `Toffy22Cj` fusionó #106 y #107. Los documentos que dicen "Visto bueno de Carlos en la revisión del PR" no tienen esa revisión en GitHub | `ficha-R6-datos-estilo-v2.md:4`; `ADR-051:321`; `gh pr view` de #104 a #112 |
| **H-2** | El código del Bloque B (#113) sale de un plan aprobado por **Manuel** (`5b8e30f`), no por Carlos. Con el criterio de este encargo, **no tiene aprobación citable de Carlos**. No se toca: se anota | `git show 5b8e30f` |
| **H-3** | La Enmienda 5 de ADR-037 ya está marcada APROBADA en `develop`. Lo que se cierra (hora y medio) es un dato que da Manuel, sin enlace ni commit de Carlos | `ADR-037-enmienda-5-…md:5` |
| **H-4** | El plan R-3 + R-2 dice "Dueño: Carlos" y ADR-050 dice "Dueño: Identidad" (`:13`). Manuel indica que el dueño es él. No hay texto de Carlos citable sobre el cambio | `plan-r3-r2-…md:7`; `ADR-050:13` |
| **H-5** | Tres documentos normativos o de plan viven **fuera de Git**: el plan R-3 + R-2 y la ficha y el plan de R-1 (`~/Escritorio/`). `plan-fase7-pantallas.md` está en otro repositorio (PaxFide) | §0 |
| **H-6** | La ficha R-6 cita "`paxfide-web/Documentos/prompts-agentes-estilo-v2.md` §3.4" (`:7`, `:177`); `plan-fase7-pantallas.md` (PaxFide) es quien hoy lleva la tabla de ⛔ (`:84`) | §0 |
| **H-7** | La memoria libre es baja: `free -g` dio **1 GB disponible** el 2026-10-24, por debajo de los 4 GB que exige `ci-local.sh` | `free -g` |

---

## 5. Ramas remotas

### 5.1 Ya fusionadas en `develop` (`git branch -r --merged origin/develop`): se pueden borrar

Solo la lista; **no se borra ninguna**.

```
chore/ci-local                      docs/plan-b1bis                    feat/integridad-en-seguimiento
chore/cierre-encargo6               docs/plan-b6-a                     feat/lecturas-fronts-a1-a3
chore/cierres-p1-encargo6           docs/plan-d-campaign               feat/miembros-invitaciones
chore/docs-adr051-nota-editorial    docs/propuesta-d-api               feat/mis-convocatorias
chore/docs-cierre-adr045-enmienda2  docs/propuesta-d-ia                feat/organizaciones-registro-y-cola
chore/docs-cierre-dp8               docs/s05-qr-tracking-cop           feat/p1-anclaje-e2e
chore/docs-correccion-auditoria-fase6  feat/administradores-plataforma feat/p1-camino-a-http
chore/docs-fase7-r6-a2c-tarea2      feat/app-adr045-orquestador        feat/p1-narrativa-individual
chore/predictor-en-repo             feat/app-adr051-t2-logo            feat/p2-endpoints
docs/adr029-enmienda1-campaignref   feat/b1bis-split-saga              feat/p3-predictor
docs/adr047-aprobado-b3-dsplit      feat/b3-jwt                        feat/prediccion-historica
docs/adr047-jwt                     feat/b4-collecting-failed          feat/predictive-dataset-baseline
docs/b-proj-plan                    feat/b4-verificacion-recalculada   feat/r6-bloque-a
docs/cierre-encargos-3-y-4          feat/b5-narrativa-convocatoria     feat/semilla-demo
docs/correcciones-auditoria-4-3     feat/b6-0-base-http                fix/b-proj-proyecciones-v2
docs/cv01-adr037e3-adr048-planb60   feat/b6-a-convocatoria-http        fix/cierre-asignaciones-historicas
docs/decisiones-b-proj-adr029       feat/b6-b-donacion-pago-http       fix/dd53-clave-obligatoria
docs/decisiones-b3-dsplit           feat/b6-c-activos-http             fix/dd53-cursor-opaco
docs/decisiones-b6-0-e3-adr048      feat/b6-d-seguimiento-http         fix/emails-minusculas
docs/decisiones-d-api               feat/configuracion-con-aprobacion  fix/fund-not-found-independiente
docs/decisiones-delegadas-b6c       feat/contracts-campaign-auditfacts-port  fix/h-p12-1-narrativa-sin-citas
docs/enmienda-sagas-aprobada        feat/convocatoria-a2-historial     fix/indices-explicitos
docs/enmienda-sagas-b1bis           feat/convocatoria-adr045-recuperacion  fix/narrativa-fallback-cacheado
docs/p4-adr042-enmienda-jmx         feat/core-t1-genesis-sin-reintento fix/p2-hallazgos
                                    feat/d-campaign-campaignref        fix/prediccion-fuera-de-rango
                                    feat/demo-local-ganache            fix/proyeccion-alta-forma-completa
                                    feat/fase6-convocatoria            fix/proyeccion-donacion-alta-atomica
                                    feat/identity-adr-038              fix/ratificaciones-dd18-iae
                                    feat/identity-s18-estado-organizacion  fix/respaldo-narrativa-runbook-cors
                                                                       fix/s03-cors
                                                                       fix/tests-transaccionales-core
```

**84 ramas** (todas con prefijo `origin/`).

### 5.2 No fusionadas por Git, pero con su PR fusionado y su contenido ya en `develop`: revisar antes de borrar

| Rama | PR | Por qué Git no la ve fusionada | Contenido |
|---|---|---|---|
| `feat/app-adr051-media-storage` | #107, fusionado | Dos commits posteriores a la fusión (`527db06`, `6dcd5f4`) | La evidencia `ci-local-527db06-…txt` es idéntica en `develop`. `ADR-051` solo difiere en las comillas de una ruta del criterio 12 (`develop` tiene la versión de #108) |
| `docs/b-proj-plan-y-q5` | #38, fusionado | Su último commit (`350bf66`) es una versión anterior de `plan-b-proj.md` | `develop` tiene una versión posterior (13 líneas más y 9 menos) |

### 5.3 No fusionadas y no son de Manuel: no se tocan

| Rama | Autor del último commit | PR | Qué tiene |
|---|---|---|---|
| `chore/front-fase1-env` | Carlos, 2026-09-26 | ninguno | `front-fase1.md` y `.env.example`, distintos de los de `develop` |
| `claude/bold-davinci-48ojej` | Claude, 2026-10-11 | ninguno | Estadística pública de donaciones (`convocatoria`) y un script del predictor |
| `claude/focused-goodall-meb6sh` | Claude, 2026-10-21 | ninguno | Lo anterior, más biometría con credencial de dispositivo (`identity`) y lista de convocatorias para el `REPRESENTATIVE`. **Código no fusionado y sin ADR citado en esta auditoría**; de quién es, lo dice Carlos |

### 5.4 Abiertas con PR

`feat/r6-bloque-b` (#113) y `chore/docs-r6-planes-c-d` (#114).

---

## 6. Notas posteriores (2026-10-24, tras la respuesta de Manuel a la revisión)

*Se añaden; nada de lo anterior se reescribe.*

- **Delegación.** Según Manuel, un mensaje de Carlos del 2026-10-24 (WhatsApp) delega en Manuel la aprobación de los planes y de los puntos [Plan] y [Manuel]. Carlos aprueba cada PR con Approve en GitHub. **El texto literal no llegó a la sesión;** queda pendiente de registrar en `decisiones-delegadas-2026-10.md`.
  - *Actualización (2026-10-24):* el texto ya está **registrado literal** en `decisiones-delegadas-2026-10.md`, nota del 2026-10-24.
  - Con esa delegación, el criterio de §1 ("solo cuenta un texto de Carlos") se sustituye, para los planes, por el "plan aprobado" de Manuel.
  - **Por eso H-2 deja de ser un hallazgo** para la aprobación del plan B, que dio Manuel en `5b8e30f`. **H-1 sigue en pie**: esos PR se fusionaron sin Approve de Carlos en GitHub. Va al informe como lista para Carlos.
- **C-1: cerrado por Manuel con la opción (b).** La premisa era errónea: el génesis tiene `sequence` 1.
- **B-8: resuelto según Manuel.** Las versiones son `v1-2026-10` en los tres `traceability.legal.*-version`, y los textos van en `paxfide-web/Documentos/legal/`. Comprobado el 2026-10-24: **esa carpeta no existe** en ninguna rama de PaxFide (`develop` `bd099a3`).
- **Nuevos pendientes, de `ficha-R6-enmienda-1.md` (PROPUESTA, Carlos, 2026-10-24; copia en `~/Escritorio/`):**
  - **M-20:** E2 (G-9), `lastEventAt?` en `GET /organizations/{id}/physical-assets`. Va en el Bloque B, dentro del #113. Hoy no existe: `PhysicalAssetOperationalView` no tiene ese campo.
  - **M-21:** E1, `eventHash` y `anchorProof?` en el historial. Va en el Bloque C.
- **Orden nuevo:** el de Carlos, transmitido por Manuel. Está en la versión 2 del plan de ejecución, §2.2.
