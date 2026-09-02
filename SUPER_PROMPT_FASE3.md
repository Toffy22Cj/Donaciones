# SÚPER PROMPT — FASE 3: EXPOSICIÓN API Y EXPERIENCIA DEL DONANTE

**Proyecto:** Motor de Trazabilidad Verificable de Donaciones (Nexxus)
**Fase actual:** Fase 3 — API REST y Experiencia del Donante
**Punto de Partida:** El Core Domain, Persistencia Event Sourced, CQRS, Criptografía (Merkle), IA (Audit Facts) y Blockchain (Web3j) están 100% implementados, probados con Testcontainers, y formalmente cerrados.

Este documento sirve como el contexto maestro para el Agente que ejecutará la Fase 3. 

---

## 1. Regla de Oro de Ejecución

> Eres un Ingeniero de Software Senior construyendo la capa de exposición (API REST) para un sistema de trazabilidad de donaciones basado en Event Sourcing y CQRS. 
> Tu rol es **NO TOCAR** el dominio (paquetes `core.domain`, `core.application`, `crypto`, `ai` o `core.infrastructure.persistence`). 
> Todo el Core funciona perfectamente. Tu trabajo es exponerlo mediante APIs limpias, seguras y bien documentadas.

**Si para implementar un endpoint REST crees que necesitas modificar un Aggregate o un Handler existente, DETENTE. Estás rompiendo el encapsulamiento. Consulta al usuario.**

---

## 2. Stack Tecnológico Restrictivo

- **Java 21**
- **Spring Boot 3.4.4** (Spring Web, Spring HATEOAS, Springdoc OpenAPI)
- **Cero lógicas de negocio en los Controladores**. Los controladores solo delegan a los Application Services / Ports ya existentes.
- **Trazabilidad y Observabilidad**: Implementación de ProblemDetails (RFC 7807) para el manejo de excepciones de dominio (ya creadas en `core.domain.shared.exceptions`).

---

## 3. Estado de la Arquitectura Actual (CQRS)

Entiende la separación estricta que ya existe en el sistema:

### Capa de Escritura (Comandos)
Se inyectan los casos de uso o el publicador de eventos genérico (`TransactionalEventPublisher`). Los Controladores REST de escritura recibirán DTOs de entrada y enviarán Comandos, devolviendo HTTP 202 (Accepted) o HTTP 201 (Created) con un identificador de seguimiento (`commandId` o `eventId`).
- Ejemplo: `POST /api/v1/funds` -> Envía `RegisterFund` -> Retorna 201 con `fundId`.

### Capa de Lectura (Proyecciones)
Se consultan directamente las vistas materializadas en MongoDB (colección `donation_projections`). 
- Ejemplo: `GET /api/v1/funds/{fundId}` -> Consulta `DonationProjectionRepository`.
- **Regla Crítica**: Los controladores REST NUNCA leen del Event Store directamente, solo leen de las proyecciones o a través de los puertos correspondientes de lectura.

---

## 4. Objetivos de la Fase 3

1. **API REST para Entidades Principales**:
   - `FundController`: Endpoints para crear promesa, liquidar fondos, y re-asignar.
   - `PhysicalAssetController`: Endpoints para logística (dispatch, receive, deliver, split).
   
2. **API de Exposición de Trazabilidad para el Donante**:
   - Endpoint público (ej: `GET /api/v1/traceability/{fundId}`) que exponga la vista completa de una donación, incluyendo su estado financiero, su historial logístico materializado (las hojas hijas del Asset) y los enlaces (Hashes de Merkle).
   
3. **Manejo Estándar de Errores**:
   - ControllerAdvice centralizado que traduzca las excepciones base (`DomainInvariantViolationException`, `ConcurrencyConflictException`) en respuestas HTTP estandarizadas según RFC 7807.

4. **Documentación Swagger / OpenAPI**:
   - Decorar los endpoints para generar una especificación clara.

---

## 5. Instrucciones para la Ejecución

- No introduzcas dependencias de seguridad (Spring Security, OAuth2) a menos que se te indique explícitamente. Asume que el API Gateway o un filtro de edge maneja la autenticación por ahora, a menos que recibas un requerimiento específico de autorización por Roles (RBAC).
- Los nombres de los endpoints deben usar sustantivos plurales (`/api/v1/funds`, `/api/v1/assets`).
- Mapea correctamente los campos: nunca expongas la versión de la base de datos de Spring Data (`@Version`) o las identificaciones internas de Mongo en los responses REST. Define Response DTOs.
- Todos los tests de los Controladores REST deben utilizar `@WebMvcTest` y mockear la capa de aplicación/puertos. No levantes todo el contexto con Testcontainers para pruebas puras de controladores.

**Inicia tu trabajo analizando el Application Layer actual para identificar qué interfaces/puertos están listos para ser cableados a los controladores.**
