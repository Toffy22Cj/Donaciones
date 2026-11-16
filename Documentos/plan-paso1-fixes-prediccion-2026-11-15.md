# Plan del paso 1 — correcciones pequeñas de la predicción (encargo del 2026-11-15)

**Estado:** APROBADO por Manuel el 2026-11-15 (respuesta «aprobado» en la sesión de trabajo, a la propuesta que este fichero recoge, con las recomendaciones tal cual). Redactado por Manuel con asistencia de Claude.
**Base:** `develop` del backend `87a39ae`, del móvil `12be877`. Rama de trabajo: `claude/focused-dirac-8besqk` (creada desde `develop`), con PR hacia `develop`.
**Alcance:** los puntos 5 (mensaje del móvil), 6 y 7 de las decisiones del 2026-11-15, más el borrador de la Enmienda 6 de ADR-037. Nada más.

## Decisiones aprobadas con el plan

1. Hasta la Tarea D no se tocan `estado-fase6.md` ni `documento-maestro-proyecto.md`: los modifica la rama abierta `claude/festive-newton-687sd0`. Cada PR deja su constancia en la referencia de API, en la nota fechada del ADR afectado y en la evidencia de CI; las entradas de estado se escriben todas en la Tarea D.
2. Texto del móvil cuando el listado responde 403: «Tu cuenta todavía no puede ver el listado de convocatorias de tu organización.»
3. Texto de la advertencia del móvil: el de la web, «ESTIMACIÓN — modelo entrenado con datos sintéticos».
4. Enmienda 6 de ADR-037, opción (a) (misma respuesta para el representante); el borrador va en el PR del backend, en estado PROPUESTO y sin código.

## Backend — `FUTURE_CUT` en convocatorias cerradas

- **Defecto.** `CampaignPredictionHistoryUseCase.cut` comprobaba el corte futuro antes que el cierre. Una convocatoria cerrada antes de tiempo respondía `FUTURE_CUT` («Este corte aún no ha llegado») en cortes que nunca llegarán. Reproducido contra la demo local el 2026-11-15 (convocatoria «Útiles escolares 2026», cerrada).
- **Contrato.** `referencia-api-v1.md`, fila de `/prediction/history`: «`CAMPAIGN_ENDED` (cerrada antes del corte)». El código lo incumplía en este caso: no hace falta ADR.
- **Cambio.** El cierre se comprueba antes que el corte futuro. No cambian los textos ni la forma de la respuesta.
- **Test rojo primero.** `CampaignPredictionHistoryUseCaseTest.aClosedCampaign_withCutsStillAheadInTheCalendar_saysCampaignEnded_notFutureCut`: cerrada el día 1, consultada el día 2; los tres cortes dan `CAMPAIGN_ENDED`, ninguno `FUTURE_CUT`, y ninguno lleva cifras.
- **No cambian:** el caso de convocatoria abierta con corte futuro (sigue `FUTURE_CUT`) ni `CampaignPredictionHistoryEndToEndTest`.
- **Documentación:** nota fechada en `referencia-api-v1.md`.

## Móvil — mensaje falso del listado (punto 5)

- **Defecto.** `_loadChoices` (`prediction_screen.dart`) convertía cualquier error en lista vacía: con un 403 (el `REPRESENTATIVE`) o sin red se leía «Tu organización todavía no tiene convocatorias».
- **Cambio.** Tres resultados distintos: listado vacío de verdad → el mensaje actual; 403 sin ningún listado → el texto de la decisión 2; otro error → el mensaje de error habitual con «Reintentar».
- **Tests (rojos primero)** en `test/flows/prediction_flow_test.dart`, con aserción negativa sobre `prediction.noCampaigns` en los dos casos de error.

## Móvil — advertencia de datos sintéticos (punto 6)

- **Respaldo.** ADR-044, Enmienda 1 («la advertencia es obligatoria»), y la decisión de Manuel del 2026-11-15. Se revierte, solo en esto, la decisión de Adonis del 2026-11-11.
- **Cambio.** La tarjeta muestra siempre la advertencia (decisión 3), con cifra y sin cifra. No se restauran la versión del modelo, la fecha ni otras cifras.
- **Tests.** Las aserciones que hoy exigen que la etiqueta no aparezca pasan a exigirla; las demás aserciones negativas se conservan.
- **Constancia.** `PENDIENTES_ADONIS.md`.

## Verificación

- Backend: `mvn test -pl app` y `scripts/ci-local.sh` sobre el último commit de código, con el resumen literal de Surefire; respuesta real de `/prediction/history` de la convocatoria cerrada de la demo local.
- Móvil: `flutter analyze`, `flutter test` y `scripts/ci-local.sh`, con su salida literal.

## Fuera de este paso

- El código de la Enmienda 6 de ADR-037 (espera su aprobación y su plan).
- Los datos de muestra, los gráficos, las fotos y la documentación consolidada (pasos 2 a 5).
