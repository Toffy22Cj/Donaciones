package com.traceability.ai.infrastructure.openai;

import com.traceability.ai.domain.exception.NarrativeProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Los modelos de razonamiento (p. ej. {@code openai/gpt-oss-20b} en Groq) a veces devuelven el contenido vacío: todo
 * se queda en su razonamiento interno. Sin esto, el conversor JSON fallaba con "No content to map due to end-of-input"
 * y el relato caía al respaldo. Se reintenta una vez; si vuelve vacío, es un fallo del proveedor con un mensaje claro.
 */
final class NonEmptyReply {

    private static final Logger log = LoggerFactory.getLogger(NonEmptyReply.class);
    static final int ATTEMPTS = 2;

    private NonEmptyReply() {
    }

    static String get(Supplier<String> call) throws NarrativeProviderException {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String text = call.get();
            if (text != null && !text.isBlank()) {
                return text;
            }
            log.warn("llm.empty-reply attempt={}/{}", attempt, ATTEMPTS);
        }
        throw new NarrativeProviderException("The LLM returned an empty reply " + ATTEMPTS + " times", null);
    }
}
