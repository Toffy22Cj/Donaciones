# Excepciones del escaneo de dependencias (T-07, ADR-055)

El escaneo (`mvn -Psecurity -DskipTests verify`) falla con cualquier vulnerabilidad de **CVSS ≥ 7**. Solo se puede suprimir una con una entrada aquí y su `<suppress>` en `dependency-check-supresiones.xml`. Las dos llevan la misma fecha de caducidad (`until`). Pasada esa fecha, la supresión deja de aplicarse y `scripts/seguridad/comprobar-supresiones.py` falla hasta que se borra o se renueva con un motivo nuevo.

Formato de cada entrada:

```
## EXC-NNN — <CVE> en <grupo:artefacto:versión>
- Dueño: <persona>
- Caduca: AAAA-MM-DD
- Por qué no aplica o no se puede actualizar todavía: <motivo verificable>
- Qué la cierra: <versión o cambio que la elimina>
```

Para la web (`pnpm audit`) y el móvil (`flutter pub outdated`) rige el mismo umbral; sus excepciones van también aquí, con el prefijo del repositorio en el título.

## Registro

Ninguna excepción vigente.
