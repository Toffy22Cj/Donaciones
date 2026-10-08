package com.traceability.core.application.integrity;

import com.traceability.contracts.SequenceRange;

import java.util.List;
import java.util.Map;

/**
 * Comprueba los eventos de una cobertura contra lo guardado: recalcula cada {@code eventHash} desde su payload y la
 * cadena {@code previousHash} (Enmienda 1 de ADR-039, B-9). Devuelve solo los eventos con algún problema.
 */
@FunctionalInterface
public interface EventIntegrityChecker {

    enum Problem {
        /** El {@code eventHash} recalculado no coincide con el guardado. */
        HASH_MISMATCH,
        /** {@code previousHash} no es el {@code eventHash} del evento anterior (o el anterior no existe). */
        CHAIN_BROKEN,
        /** Evento anterior al corte de {@code 0579f41} que no coincide con ninguna de las dos formas canónicas. */
        CANONICAL_FORM_UNKNOWN
    }

    record Finding(String streamId, long sequence, Problem problem) {}

    List<Finding> check(Map<String, SequenceRange> coverage);
}
