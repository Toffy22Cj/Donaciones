package com.traceability.core.application.integrity;

import java.util.HashMap;
import java.util.Map;

/**
 * Forma canónica anterior a {@code 0579f41} (2026-09-16), solo para <b>verificar</b> eventos guardados antes del corte:
 * la actual más {@code actorRef} como texto, como hacía {@code EventCanonicalMapper.toCanonicalMap} en {@code 0579f41^}.
 * Componente aparte y de solo lectura: no amplía la firma del mapper actual (Enmienda 1 de ADR-039, P3).
 */
final class LegacyCanonicalForm {

    private LegacyCanonicalForm() {}

    static Map<String, Object> of(Map<String, Object> currentForm, String actorRef) {
        Map<String, Object> legacy = new HashMap<>(currentForm);
        legacy.put("actorRef", actorRef);
        return legacy;
    }
}
