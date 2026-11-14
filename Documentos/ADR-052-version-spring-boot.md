# ADR-052 — Versión de Spring Boot: parche 3.4.13 y decisión sobre la línea sin soporte

## Status

**PROPUESTO — falta la decisión del aprobador entre B y C (pregunta 2 del plan, §9).**

El usuario aprobó el plan `plan-evolucion-producto.md` el 2026-11-14. Ese plan incluye ya el **paso 1** (subir al parche 3.4.13), que no necesita esta decisión y está implementado en la misma rama que este ADR (§Decision, D1). Las decisiones D2 y D3 quedan abiertas: el plan no recomienda B ni C y la decisión es del aprobador.

Enmienda, al aprobarse, `documento-maestro-proyecto.md` §3, que congela «Spring Boot 3.4.4».

## Context

- El proyecto usaba Spring Boot 3.4.4 (`pom.xml`, `spring-boot.version`). La línea 3.4 ya no recibe parches OSS. 3.4.13 es el último parche publicado de la línea; se comprobó en Maven Central el 2026-11-14 (`spring-boot-dependencies/maven-metadata.xml`: 3.4.10, 3.4.11, 3.4.12, 3.4.13).
- La línea 4.x tiene soporte OSS. La 4.1 lo tiene hasta julio de 2027, según el informe `PRODUCT_EVOLUTION_AND_MATURITY_REPORT.md`; el dato no se ha vuelto a comprobar aquí.
- Dependencias que fijan o limitan la migración: Spring AI 1.0.0-M6 (T-02, depende de esta decisión); Testcontainers con el *workaround* `docker.api.version=1.41` (deuda 3 del documento maestro, §9.1); `spring-boot-starter-mail`, Nimbus (ADR-047) y el SDK de AWS (ADR-051), estos dos últimos fijados a mano.
- Riesgo: un CVE en Spring Framework, Tomcat o Jackson sin parche en la línea 3.4 queda abierto mientras se siga en ella.

## Decision

- **D1 (implementado; no requiere elegir B o C).** `spring-boot.version` pasa de 3.4.4 a **3.4.13**. Solo cambia esa propiedad: el BOM arrastra los parches de Spring Framework, Tomcat, Jackson, Mongo y Testcontainers que gestiona. Se revisa el *workaround* de `docker-java.properties` con la versión de Testcontainers que trae el BOM nuevo (§Consequences).
- **D2 (pendiente).** Elegir una de las dos opciones:
  - **(B)** migrar a la línea 4.x con soporte, primero con un *spike* de 1–2 días que liste las roturas por módulo. Después, en la rama de integración `feat/spring-boot-4`, módulo a módulo empezando por `contracts` y `crypto`;
  - **(C)** aceptar el riesgo por escrito, con fecha de caducidad y la prohibición de exponer datos personales reales en público mientras dure.
- **D3 (pendiente, depende de D2).** Fecha de caducidad de la decisión. Con C, la de la aceptación del riesgo; con B, la fecha límite del *spike*.

## Alternatives

| Opción | A favor | En contra |
|---|---|---|
| A. Quedarse en 3.4.4 | Nada que hacer | Nueve parches de seguridad y correcciones sin aplicar; no hay motivo técnico para no subir |
| **A′. 3.4.13 (D1)** | Mismo API; lo justifica el reactor completo | La línea sigue sin soporte |
| B. 4.x | Soporte; base para T-02 | Coste L–XL; cambios en Jackson 3, Security y Testcontainers por evaluar en el *spike* |
| C. Riesgo aceptado con fecha | Sin coste ahora | Riesgo creciente; obliga a restringir el uso público |

## Consequences

- D1: verificado con el reactor completo (`scripts/ci-local.sh`) sobre el commit del cambio. La evidencia queda en `Documentos/evidencia-ci/`, y el resultado, con la salida literal, en `estado-fase6.md`.
- Con D1 no cambia ningún contrato HTTP ni ninguna dependencia nueva.
- Hasta que se decida D2, T-02 (Spring AI GA) no puede empezar, porque su versión depende de la de Boot.
