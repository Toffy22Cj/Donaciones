# Ficha R-6 — Enmienda 1: hash con prueba de Merkle por paso (G-3) y fecha del último cambio de cada lote (G-9)

**Estado: PROPUESTA.** **Qué** se construye lo decidió Carlos el 2026-10-24. **Cómo** se construye (los puntos marcados **[Manuel]**) lo decide Manuel por delegación de Carlos, como en §9 de la ficha. Carlos da el visto bueno en el PR.
**Fecha:** 2026-10-24 (UTC).
**Base verificada:** `develop` en `0b3dc3e`.
**Cambia:** D-R6-6 de la ficha R-6 (CONGELADA). Añade G-9. El resto de la ficha no cambia.
**Origen:** `paxfide-web/Documentos/plan-fase7-fidelidad.md`, D-F16. El boceto de la línea de tiempo muestra la "Huella criptográfica" de cada paso, y el del panel muestra la columna "Último cambio" de cada lote.

---

## E1. G-3 con hash y prueba de Merkle (reabre D-R6-6)

### E1.1 Por qué se reabre

D-R6-6 dejó fuera el hash del evento porque "un hash recortado no le sirve al donante para verificar nada". Es cierto para el hash **solo**. Carlos eligió el 2026-10-24 la versión que sí verifica algo: el hash **con su prueba de inclusión**. Con la prueba, el navegador del donante recalcula la raíz de Merkle y comprueba que ese paso está dentro del lote anclado en la blockchain, sin confiar en lo que el servidor diga del resultado. Es la propiedad que da nombre al proyecto ("trazabilidad verificable").

**Decisión nueva (D-R6-6, Enmienda 1, Carlos, 2026-10-24):** cada paso del historial lleva `eventHash` y, cuando su lote está `ANCHORED`, `anchorProof`. Los cuatro valores de `anchorStatus` de la ficha (§5, C-4) no cambian.

### E1.2 Contrato (aditivo)

`GET /api/v1/donations/tracking/assets/{assetRef}/history`. Cada ítem de `history` gana dos campos, además de `anchorStatus` (ficha §5):

| Campo | Cuándo llega | Contenido |
|---|---|---|
| `eventHash` | Siempre | El `eventHash` del evento en el Event Store: 64 caracteres hexadecimales en minúscula |
| `anchorProof?` | Solo si `anchorStatus = ANCHORED` y el lote tiene `leafHashes` guardados | `{merkleRoot, leafIndex, leafCount, path: [{side: "LEFT"\|"RIGHT", hash}], transactionHash, network, anchoredAt}` |

- Sigue sin llevar `sequence` ni `streamId` (DD-76). `leafIndex` es la posición dentro del lote, no la secuencia del stream.
- **Lote antiguo sin `leafHashes`** (`LegacyBatchLeafHashesUnavailableException` en `IntegrityVerificationUseCase`): sin `anchorProof`. No se reconstruye la prueba con las hojas actuales, porque esas no son necesariamente las que produjeron la raíz anclada.
- **Si el `eventHash` actual del evento no es igual a `leafHashes[leafIndex]`:** sin `anchorProof`, y el paso pasa a `anchorStatus = UNDER_REVIEW`. Nunca se devuelve una prueba que no corresponda al hecho mostrado (regla 2.2).

### E1.3 Algoritmo (el de `MerkleTree.build`, verificado en `develop` `0b3dc3e`)

Hechos del código:
- **Hojas:** los `eventHash` del lote, en el orden en que `getEventHashesByCoverage(coverage)` los devuelve: streams ordenados por `streamId` y, dentro de cada uno, por `sequence`. Se guardan en `MerkleBatch.leafHashes`.
- **Nodo padre:** `SHA-256( UTF-8( izquierdo_hex + derecho_hex ) )` en hexadecimal minúscula. Se concatenan los **textos hexadecimales**, no los bytes.
- **Número impar de nodos en un nivel:** el último se combina consigo mismo.

La prueba se construye con `leafHashes` guardados:

```
nodo = eventHash
para cada paso de path, en orden (de la hoja hacia la raíz):
  si side == "LEFT":  nodo = sha256_hex(paso.hash + nodo)
  si side == "RIGHT": nodo = sha256_hex(nodo + paso.hash)
válido  ⇔  nodo == merkleRoot
```

Cuando el nodo es el último de un nivel impar, su paso es `{side: "RIGHT", hash: <el mismo nodo>}`.

**[Manuel]** Dónde vive la construcción: se propone una función pura `MerkleTree.proof(List<String> leaves, int index)` en `crypto` (el dueño del algoritmo), que reutiliza el mismo `hashPair`. Se expone a `app` con un puerto **nuevo** de solo lectura. Si el plan necesita cambiar un puerto existente, rige la regla 3.5 (ADR) y se DETIENE.

### E1.4 Riesgos y límites

- **Sin separación de dominio entre hojas y nodos internos.** El árbol no antepone un prefijo distinto a las hojas y a los nodos, y repite el último nodo de un nivel impar. Por eso, en teoría, admite segundas preimágenes de árbol. **No se cambia:** cambiar el algoritmo invalidaría las raíces ya ancladas, y eso necesitaría un ADR. Se documenta en `referencia-api-v1.md` y en el texto de ayuda de la web.
- **Qué se expone:** los hermanos de la prueba son hashes de otros eventos del mismo lote (de otras donaciones). Un hash no revela el contenido. Sí revela el tamaño del lote (`leafCount`). **[Manuel]** Confirmar que es aceptable, o proponer ocultar `leafCount`, que el navegador no necesita.
- **Qué prueba y qué no.** Prueba que el hash de ese paso está dentro de la raíz anclada en esa transacción. No prueba que el hash corresponda al contenido del evento: el contenido no se expone. Esa comprobación sigue siendo la de `GET …/integrity` (P4), en el servidor. La web lo dice con esas palabras (plan de fidelidad, F-C2).
- **La transacción:** la web enlaza al explorador de la red para que una persona compruebe que la raíz está en la cadena. El navegador no consulta la blockchain: no hay clave de proveedor en el cliente.

### E1.5 Tests (rojos primero)

- **`MerkleTreeTest` (ampliado):**
  - para listas de 1 a 33 hojas (con números impares), toda prueba de cada índice reconstruye `getRoot()`;
  - cambiar un carácter de un paso o de la hoja la invalida (negativa).
- **Vectores fijos** (hojas, índice, prueba, raíz) en un archivo de recursos, para que la web los use en su test de F-C2. Uno con 5 hojas.
- **Integración (Testcontainers):** un lote `ANCHORED` → `eventHash` y `anchorProof` válidos. Además:
  - lote `PENDING` → sin `anchorProof`;
  - lote antiguo sin `leafHashes` → sin `anchorProof`;
  - evento alterado → `UNDER_REVIEW` y sin `anchorProof`;
  - cada caso, con aserciones negativas de las otras ramas.
- La respuesta sigue sin `sequence` ni `streamId` (negativa).

---

## E2. G-9 — Fecha del último cambio de cada lote (nuevo)

- **Endpoint:** `GET /api/v1/organizations/{organizationId}/physical-assets`.
- **Cambio:** cada ítem gana `lastEventAt?` (ISO-8601 UTC): el `occurredAt` del último evento del stream del lote, **por orden de `sequence`**, nunca por timestamp. Es el mismo criterio que D-R6-2.
- **Dónde:** se lee del Event Store al consultar, por el mismo motivo que C-2 (no hay un disparador operativo para reconstruir proyecciones). **[Manuel]** Cómo hacerlo sin una consulta por lote: una agregación por página sobre los `streamId` de la respuesta, con el último por `sequence`, cubierta por el índice `(streamId, sequence)`. El plan lo mide con `explain`, como A2.
- **Si falta** (por ejemplo, un lote sin eventos legibles), el campo se omite. La web no muestra "hace 0 días".
- **Tests:**
  - lote con varios eventos → la fecha es la del de mayor `sequence`, aunque otro tenga un `occurredAt` mayor (negativa del orden por tiempo);
  - página con varios lotes → una sola consulta de agregación (se cuenta).

---

## E3. Orden

G-9 entra en el **bloque B**, con G-2 y G-6, porque es un dato con fecha. E1 entra en el **bloque C**, con G-3 y G-4. Siguen siendo un PR por bloque (D-R6-10).

## E4. Documentos que se actualizan con cada PR

Los de §8 de la ficha, más:
- `referencia-api-v1.md`: notas fechadas de G-9 y de E1, con el algoritmo de E1.3 y su límite de E1.4;
- en la ficha R-6, una nota en §9 junto a D-R6-6 que remite a esta enmienda.
