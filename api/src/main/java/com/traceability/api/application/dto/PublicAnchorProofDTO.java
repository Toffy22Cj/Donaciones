package com.traceability.api.application.dto;

import com.traceability.contracts.AssetAnchorStatusPort.ProofStep;

import java.util.List;

/**
 * {@code anchorProof} de un paso del historial (ficha R-6, Enmienda 1, E1.2), sin {@code leafCount} (plan de
 * ejecución, §0.4). {@code path} va de la hoja hacia la raíz: {@code [{side: "LEFT"|"RIGHT", hash}]}. Verificar
 * (E1.3): {@code nodo = eventHash}; {@code LEFT → sha256_hex(hash + nodo)}; {@code RIGHT → sha256_hex(nodo + hash)};
 * válida si {@code nodo == merkleRoot}. {@code anchoredAt} en ISO-8601 UTC.
 */
public record PublicAnchorProofDTO(
        String merkleRoot,
        int leafIndex,
        List<ProofStep> path,
        String transactionHash,
        String network,
        String anchoredAt
) {
}
