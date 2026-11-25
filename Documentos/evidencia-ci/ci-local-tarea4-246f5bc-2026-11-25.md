# ci-local.sh de la Tarea 4 (copia censurada en el seguimiento)

## Primera corrida (commit 0b42a7a): ROJA, 2 fallos propios (E8 y una consulta extra al event store)
[INFO] Tests run: 456, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 230, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 448, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 272, Failures: 0, Errors: 0, Skipped: 0
[ERROR] Tests run: 821, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE

## Segunda corrida (commit 246f5bc): VERDE
[INFO] Tests run: 456, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 230, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 448, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 272, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 821, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

## Mutaciones (scripts/mutaciones.py; specs en este directorio): 13 de 13 muertas (9 en api, 4 en app/core), antes del arreglo de la consulta por vínculo
