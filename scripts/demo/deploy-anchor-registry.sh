#!/usr/bin/env bash
# Despliega AnchorRegistry en la Ganache local y escribe su dirección en stdout (runbook-demo-local.md, paso 3).
# Usa la cuenta determinista #1 de Ganache (desbloqueada por Ganache, sin clave en este script): la #0 queda para
# anclar con su nonce en 0 (hallazgo H-P14-1).
set -euo pipefail
RPC=${GANACHE_URL:-http://localhost:8545}
ROOT=$(git rev-parse --show-toplevel)
BIN="0x$(tr -d '[:space:]' < "$ROOT/crypto/src/test/resources/solidity/build/AnchorRegistry.bin")"
DEPLOYER=0xffcf8fdee72ac11b5c542428b35eef5769c409f0

rpc() { curl -sf -X POST -H 'Content-Type: application/json' --data "$1" "$RPC"; }

TX=$(rpc "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_sendTransaction\",\"params\":[{\"from\":\"$DEPLOYER\",\"gas\":\"0x2dc6c0\",\"data\":\"$BIN\"}]}" \
     | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["result"]) if "result" in r else sys.exit("error: %s" % r)')
for _ in $(seq 1 20); do
    ADDR=$(rpc "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"eth_getTransactionReceipt\",\"params\":[\"$TX\"]}" \
           | python3 -c 'import json,sys; r=json.load(sys.stdin).get("result"); print(r["contractAddress"] if r else "")')
    if [ -n "$ADDR" ]; then echo "$ADDR"; exit 0; fi
    sleep 1
done
echo "deploy-anchor-registry: sin recibo para $TX" >&2
exit 1
