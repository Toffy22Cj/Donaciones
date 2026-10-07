# Guía de Onboarding para Desarrolladores

¡Bienvenido al equipo de desarrollo del Motor de Trazabilidad Verificable de Donaciones! Este documento sirve como punto de partida rápido para que te familiarices con la base de código, la arquitectura y nuestras reglas de desarrollo.

> **Nota sobre el nombre:** en la conversación de diseño el proyecto se referenció informalmente como "PaxFide". Es un nombre comercial **provisional y no vinculante** — puede cambiar sin afectar el dominio, la arquitectura ni el código. No lo uses en nombres de paquete, clases, ni identificadores (el groupId real es `com.traceability`).

---

## 1. ¿Qué es este sistema?

Es una plataforma backend para rastrear donaciones con integridad absoluta. En lugar de usar un sistema CRUD donde la información se sobrescribe, utilizamos **Event Sourcing**: cada cambio (registro de donación, compra de recursos, despachos logísticos) es un evento inmutable guardado de por vida.
Además, aseguramos que nadie pueda alterar la historia hasheando cada evento (SHA-256) y anclando el registro completo de manera periódica en una **Blockchain** (vía Árboles de Merkle). También tenemos un traductor de **Inteligencia Artificial** que lee este historial y lo traduce a lenguaje natural sin exponer datos personales (PII).

---

## 2. Estado Actual de la Arquitectura (Monolito Modular)

Actualmente, el backend está estructurado en 5 módulos:

1. **`core`**: El corazón del sistema. Contiene el Domain-Driven Design (DDD), las reglas de negocio (Fund, PhysicalAsset) y la persistencia de los eventos en MongoDB mediante Event Sourcing. También gestiona proyecciones CQRS.
2. **`contracts`**: Contratos puros, DTOs e interfaces (Ports). Este módulo no tiene lógicas ni dependencias pesadas, y permite que los otros módulos se hablen entre sí de manera desacoplada.
3. **`crypto`**: Encargado de la serialización criptográfica (RFC 8785), generación de árboles de Merkle y un motor tolerante a fallos para publicar hashes de forma asíncrona hacia una red EVM pública usando Web3j (Scheduler y Poller).
4. **`ai`**: Módulo que consume la auditoría del sistema para usar un LLM (vía Spring AI) generando reportes de lenguaje natural verificables. Funciona bajo el principio de "Confianza Cero" hacia la IA (grounding determinista) y sin acceso a PII.
5. **`app`**: Módulo de ensamblaje (Bootstrap). Es el único punto de entrada real (`@SpringBootApplication`) — depende de `core`, `crypto` y `ai`, y provee la infraestructura compartida (ej. `MongoTransactionManager` canónico, `application.yml` centralizado). No contiene lógica de dominio propia.

> **Estado del ensamblaje:** el sistema ya arranca como un único proceso Spring Boot real — verificado con `ApplicationContextLoadTest` contra Testcontainers, incluyendo un smoke test transaccional de punta a punta. **Todavía no existe capa de exposición HTTP/REST** — `app` deliberadamente no incluye `spring-boot-starter-web` en esta fase; eso es alcance de la Fase 3, aún sin definir formalmente (ver `plan-ejecucion-agentes-fase2.md` sección 6).

---

## 3. Entorno de Desarrollo y Pruebas

Para desarrollar en este proyecto necesitas:
- **Java 21**
- **Maven**
- **Docker** (Corriendo localmente, obligatorio para Testcontainers y levantar MongoDB/Ganache temporalmente).

El proyecto tiene **tolerancia cero** con el código sin probar. Toda lógica crítica, especialmente Sagas y adaptadores Blockchain, debe evidenciar ejecución genuina mediante un entorno contenerizado, validando la interacción real con la base de datos o el nodo criptográfico.

---

## 4. Estrategia de Ramas en Git y Reglas de Proceso

La política completa de ramas, protección de rama, checklist de revisión de PR, y las reglas de disciplina que aplicamos tanto a desarrolladores humanos como a agentes de código (verificación con ejecución real, cuándo se requiere un ADR, cómo se documenta el estado del trabajo) viven en **`reglas-equipo-y-agentes.md`** — léelo completo antes de tu primer PR, es lectura obligatoria de onboarding, no opcional.

Resumen rápido de ramas (la tabla completa y autoritativa está en ese documento, no aquí, para evitar que ambos textos diverjan con el tiempo):

- `main` / `develop`: producción / integración.
- `feat/`, `fix/`, `chore/`: trabajo en curso.
- Ninguna rama `feat/` se abre para una decisión arquitectónicamente significativa sin haber pasado antes por el proceso de Modo de Arquitectura + ADR.

## 5. Secretos y Claves

- `WEB3_PRIVATE_KEY` (módulo `crypto`) se inyecta exclusivamente vía variable de entorno — **nunca** en código, `application.yml`, ni Git.
- Los tests de integración de `crypto` contra Testcontainers/Ganache usan la **cuenta determinista #0 de Ganache**, un valor público conocido, sin fondos reales, marcado explícitamente en el código con un comentario de advertencia. No es un secreto real y no debe confundirse con la clave de producción.

## 6. Nota de entorno — módulo `app`

Si `mvn test -pl app` falla con `client version 1.32 is too old`, **no borres** `app/src/test/resources/docker-java.properties` — contiene un workaround deliberado y documentado (ver `documento-maestro-proyecto.md` sección 9.1, ítem 3) para un problema de negociación de versión de API de Docker específico de ese módulo, sin causa raíz identificada tras investigación exhaustiva. Es intencional, no basura de configuración.
