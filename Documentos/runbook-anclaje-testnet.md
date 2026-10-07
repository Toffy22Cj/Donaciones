# Runbook — anclaje del golden path en una testnet

**Estado:** preparado bajo la segunda autorización de trabajo autónomo de Carlos (2026-10-07), P1.4. **Lo ejecuta Carlos.** La ejecución en la testnet, las claves de blockchain y la clave del LLM son suyas: este documento nombra las variables y los pasos, **nunca valores reales**.
**Cubre:** criterios 10, 11, 12 y 18 de `golden-path.md` §8 contra una red pública de pruebas. Con una cadena local (Ganache) ya los cumple `GoldenPathHttpIntegrationTest` en cada `mvn clean test`.
**No cambia:** payloads, versiones de esquema, canonicalización, hash, Merkle ni anclaje. Solo configuración por entorno.

---

## 1. Qué hace el recorrido

`GoldenPathHttpIntegrationTest` recorre la demo por HTTP (criterios 1–9 y 13–17). Mientras tanto, los procesos reales del sistema:

1. `BlockchainAnchorProducer` reclama los eventos sin anclar y construye un `MerkleBatch` (`COLLECTING` → `PENDING`);
2. `BlockchainAnchorScheduler` asigna el nonce y envía `anchorRoot(root)` al contrato `AnchorRegistry` (`SUBMITTED`);
3. `AnchorConfirmationPoller` espera las confirmaciones, lee la raíz de la transacción y **solo** marca `ANCHORED` si coincide con la del batch;
4. al final, el test exige que todos los eventos de los dos fondos, del activo padre y del hijo estén en batches `ANCHORED`, y llama a `IntegrityVerificationPort.verifyBatch` en cada uno: `MATCH` (criterio 12).

Con `GOLDEN_PATH_ANCHOR_TARGET=testnet` los pasos 2 y 3 van a la testnet de las variables de §3 en lugar de Ganache.

## 2. Requisitos previos (una vez)

| # | Paso | Nota |
|---|---|---|
| 1 | Una **wallet dedicada solo al anclaje**, creada para esto y con fondos de un *faucet* de la testnet | Nunca una wallet con fondos reales ni la de producción |
| 2 | **Otra** wallet para desplegar el contrato | Ver §5, hallazgo H-P14-1: si el despliegue sale de la wallet de anclaje, su nonce ya no es 0 |
| 3 | Un endpoint RPC de la testnet (proveedor propio o público) | La URL puede contener una clave del proveedor: es un secreto |
| 4 | Desplegar `AnchorRegistry` con la wallet del paso 2 | Bytecode compilado: `crypto/src/test/resources/solidity/build/AnchorRegistry.bin` (fuente en `crypto/src/test/resources/solidity/`). Anotar la dirección del contrato |
| 5 | Docker en marcha | El test levanta MongoDB (y Ganache, que en modo testnet no se usa) con Testcontainers |

Red por defecto de `application.yml`: Sepolia (`chain-id` 11155111).

## 3. Variables de entorno

Se exportan en la sesión de terminal de Carlos, **nunca** en un fichero del repositorio, en un commit ni en un log.

| Variable | Qué es | Ejemplo de forma (no un valor) |
|---|---|---|
| `GOLDEN_PATH_ANCHOR_TARGET` | Activa el modo testnet del recorrido | `testnet` |
| `WEB3_NODE_URL` | URL RPC de la testnet | `https://<proveedor>/<clave>` |
| `WEB3_PRIVATE_KEY` | Clave privada de la **wallet de anclaje** (§2.1) | `0x` + 64 hex |
| `CRYPTO_ANCHOR_SMART_CONTRACT` | Dirección del `AnchorRegistry` desplegado (§2.4) | `0x` + 40 hex |
| `CRYPTO_ANCHOR_NETWORK` | Nombre lógico de la red; forma parte de la clave del contador de nonce | `sepolia` |
| `CRYPTO_ANCHOR_CHAIN_ID` | Chain id de la red | `11155111` para Sepolia |
| `CRYPTO_ANCHOR_CONFIRMATIONS_REQUIRED` | Confirmaciones antes de `ANCHORED` | `12` (valor de `application.yml`) |

Si falta una, el test se detiene con "Falta la variable de entorno X" sin mostrar ningún valor.

`SPRING_AI_OPENAI_API_KEY` no hace falta: el recorrido usa un LLM simulado para las narrativas (criterios 13, 14 y 19).

## 4. Ejecución

```bash
# en la raíz del repositorio, rama develop actualizada
export GOLDEN_PATH_ANCHOR_TARGET=testnet
export WEB3_NODE_URL=...            # §3
export WEB3_PRIVATE_KEY=...         # §3 — no se escribe en ningún fichero
export CRYPTO_ANCHOR_SMART_CONTRACT=...
export CRYPTO_ANCHOR_NETWORK=sepolia
export CRYPTO_ANCHOR_CHAIN_ID=11155111
export CRYPTO_ANCHOR_CONFIRMATIONS_REQUIRED=12

mvn -pl app -am test -Dtest=GoldenPathHttpIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false \
  | tee golden-path-testnet.log
```

- En modo testnet los procesos de anclaje van cada 5 s y el test espera el anclaje hasta 30 minutos.
- La base de datos es un MongoDB efímero de Testcontainers: el contador de nonce empieza vacío (§5).
- Al terminar, `unset WEB3_PRIVATE_KEY WEB3_NODE_URL` (o cerrar la terminal).

## 5. Nonce de la wallet de anclaje (hallazgo H-P14-1)

El nonce de cada envío sale de un contador en MongoDB (`web3_nonce_counter`, `_id = <network>-<contrato>`) que empieza en 0. `seedNonceCounter` existe en `MerkleBatchRepositoryPort` pero **nada lo llama**. Por tanto:

- la wallet de anclaje debe tener **nonce 0** en la red al empezar (por eso el contrato se despliega con otra wallet, §2.2); y
- con el MongoDB efímero del test, **cada ejecución empieza otra vez en 0**: una segunda ejecución con la misma wallet fallaría por *nonce too low*. Para repetir, usar una wallet de anclaje nueva (o un contrato nuevo **y** una wallet nueva).

Con un MongoDB persistente, la alternativa es sembrar el contador a mano antes de arrancar, con el nonce pendiente de la wallet (`eth_getTransactionCount(<wallet>, "pending")`):

```js
db.web3_nonce_counter.updateOne(
  { _id: "<CRYPTO_ANCHOR_NETWORK>-<CRYPTO_ANCHOR_SMART_CONTRACT>" },
  { $max: { nextNonce: <nonce pendiente> } },
  { upsert: true })
```

Registrado como hallazgo; no se corrige en este bloque porque toca el flujo de anclaje (fuera de lo autorizado).

## 6. Evidencia que guardar

| # | Qué | Dónde |
|---|---|---|
| 1 | Salida de Maven con `Tests run: 1, Failures: 0` de `GoldenPathHttpIntegrationTest` | `golden-path-testnet.log` (§4) |
| 2 | Fichero que escribe el test: por cada batch, `network`, `contract`, `merkleRoot`, `txHash`, `block` y `verifyBatch=MATCH` | `app/target/golden-path-anclaje-evidencia.txt` |
| 3 | Para cada `txHash`, la página del explorador de bloques de la testnet: estado *Success*, contrato destino y, en *Input Data*, la llamada `anchorRoot` con la misma `merkleRoot` | Captura o URL |
| 4 | Commit de `develop` con el que se ejecutó | `git rev-parse HEAD` |

Guardar 1, 2 y 4 (y las URL de 3) en `Documentos/evidencia-fase6/anclaje-testnet-<sha>-<fecha>.txt`. **Antes de commitear, revisar que ni el log ni el fichero contienen la clave privada ni la URL RPC**: el test no las imprime, pero un error del proveedor podría incluir la URL. Si aparece, borrarla del fichero.

## 7. Si algo falla

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| "Falta la variable de entorno X" | §3 incompleto | Exportarla |
| `nonce too low` en el log | §5 | Wallet de anclaje nueva |
| `insufficient funds` | Wallet sin saldo de testnet | *Faucet* |
| Batch en `SUBMITTED` sin pasar a `ANCHORED` hasta el tiempo límite | Pocas confirmaciones aún o RPC lento | Revisar la transacción en el explorador; subir el límite no es necesario con 12 confirmaciones |
| `ANCHOR_MISMATCH` | La raíz en cadena no coincide con la del batch | **Parar y avisar**: es justo lo que el anclaje debe detectar |
| `GasCapExceededException` | Gas de la testnet por encima de `crypto.anchor.gas.max-fee-per-gas-cap` | Reintentar más tarde; no subir el tope sin decidirlo |
