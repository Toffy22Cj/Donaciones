# ADR-035 — HumanActor como variante de ActorRef y puente de autorización humana

## Status
Approved

## Contexto
El sistema requiere establecer la representación de la identidad de un operador humano dentro del Core para posibilitar operaciones restringidas, como la donación en especie (Camino B, Tarea 5.4). Actualmente, el ADR-031 define la taxonomía de `ActorRef` y bloqueó explícitamente la creación de una cuenta humana provisional. En la actualidad, el sistema solamente despacha autorización de comandos empleando los actores `SystemActor` y `ExternalActor`, los cuales efectúan *bypass* de políticas.
Adicionalmente, se requiere resolver cómo persistir este nuevo actor sin romper la compatibilidad de los documentos existentes generados bajo Event Sourcing y conservando la coherencia con el modelo de autorización existente (puerto de Identity↔Core dictado por el ADR-032).

## Problema
- `ActorRef` requiere una variante que transporte la identidad humana sin contaminar el dominio con roles u organizaciones (`Identity` es otro Bounded Context).
- Los comandos expuestos (e.g. `registerPhysicalAssetFromDonation`) necesitan autenticación y autorización humanas.
- La persistencia de `ActorRef` en Mongo mediante conversores (`ActorRefWriteConverter` y `ActorRefReadConverter`) evalúa de forma manual y exhaustiva (mediante `instanceof` y `String`) los tipos permitidos, incurriendo en el riesgo de *fallback* silencioso en escritura que produce un documento vacío en BD.

## Decisión
Se ha decidido incorporar la representación de actor humano introduciendo el tipo `HumanActor` como variante de la interfaz sellada `ActorRef`.

### 1. HumanActor y ActorRef
Se define `HumanActor` en el Core:
```java
public record HumanActor(String accountId) implements ActorRef {}
```
Este registro es la tercera variante permitida de `ActorRef`. No incorpora identidad, información organizacional ni roles; solamente contiene el identificador unívoco de la cuenta (`accountId`).

### 2. Resolución mediante IdentityPrincipalPort
El wiring de autorización se integrará consumiendo el servicio existente `IdentityPrincipalPort`, el cual inyectará la lógica necesaria sin afectar el contrato. 
La secuencia de autorización será:
1. Se recibe un `HumanActor(accountId)`.
2. Se invoca `IdentityPrincipalPort.resolvePrincipal(accountId)`.
3. Se invoca `OrganizationBoundaryPolicy.assertBelongs(...)`.
4. Se invoca `RoleAuthorizationPolicy.authorize(...)`.
5. Si todas las validaciones son exitosas, se ejecuta la acción de dominio en el Aggregate.

### 3. Bypass SystemActor/ExternalActor
`SystemActor` y `ExternalActor` mantendrán su comportamiento actual; las políticas descritas en la secuencia anterior se ignorarán (*bypass*) para las variantes automáticas y externas del sistema.

### 4. Persistencia Mongo
El conversor MongoDB utilizará el siguiente formato persistido (retrocompatible):
```json
{
  "_class": "HumanActor",
  "accountId": "..."
}
```

### 5. Tratamiento del fallback de escritura
Se debe actualizar el mecanismo exhaustivo manual (`if/else`) en:
- **ActorRefWriteConverter:** Además de añadir la evaluación para `HumanActor`, se cambiará el fallback. Si recibe un `ActorRef` no reconocido, lanzará una excepción explícita (`UnknownActorRefTypeException`) en lugar de devolver un `Document` vacío, eliminando el riesgo silencioso.
- **ActorRefReadConverter:** Se actualizará para mapear `"HumanActor"`. Su comportamiento de fallback actual (devolver `null` para un tipo desconocido) **se conservará** y queda explícitamente fuera del alcance de esta decisión, favoreciendo la resiliencia en lecturas anómalas.

## Alternativas Consideradas
- **Actor abstracto general:** Rechazado por entrar en conflicto con la taxonomía estricta acordada en el ADR-031 y el desacople impuesto por ADR-032.
- **Inyectar la identidad completa en HumanActor:** Rechazado. Duplicaría lógica del Bounded Context de Identity en el Core.

## Consecuencias
- Desbloquea la capacidad de implementar *Command Services* que involucren seres humanos (como la Tarea 5.4).
- Extiende la persistencia polimórfica en MongoDB de forma 100% segura para la base de eventos actual.
- Fija un estándar definitivo para futuros endpoints (REST/HTTP) que consuman el Application Service con comandos humanos.

## Alcance
- Introducción de `HumanActor`.
- Inyección y cableado (*wiring*) en los Application Services existentes (`FundCommandService`, `PhysicalAssetCommandService`).
- Modificación estricta de conversores BSON.

## Fuera de Alcance
- Autenticación, JWT, inicio de sesión, credenciales o sesiones.
- Transportes externos (controladores HTTP).
- Implementación de la Tarea 5.4 (`registerPhysicalAssetFromDonation`).
- Implementación de la Tarea NUEVA-4B.
- Decisión de asignación de rol / `CommandType` para la reversión de asignación humana.

## Relación con 5.4
La Tarea 5.4 permanece formalmente bloqueada hasta que HumanActor se implemente. Una vez implementado, el comando podrá implementarse y probarse de extremo a extremo utilizando un accountId de prueba. La invocación real en producción continuará requiriendo el mecanismo de autenticación correspondiente, que queda fuera del alcance de este ADR.

## Relación con NUEVA-4B
NUEVA-4B representa la visibilidad y operabilidad de una asignación en estado pendiente. La invocación humana de `reverseAllocation` dependerá de `HumanActor`. No obstante, la determinación de si este comando administrativo utilizará el mismo método existente de compensación por saga o uno nuevo/distinto, es una decisión pendiente exclusiva de la tarea NUEVA-4B y no del alcance de este ADR.

## Referencias
- **ADR-031:** Taxonomía de `ActorRef` y bloqueo temporal de `HumanAccount`.
- **ADR-032:** Autorización de comandos, puerto `Identity↔Core` y matriz de roles.
