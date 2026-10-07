package com.traceability.core.application.saga;

public interface SagaPolicy {
    
    /**
     * @return Identifies the type of saga this policy handles.
     */
    String getSagaType();
    
    /**
     * Attempts the main operation (e.g., create Asset). Throws exception if it fails: {@link PermanentSagaFailureException}
     * if the domain makes it impossible, any other exception if it is transient (ADR-007/008 Enmienda 1, D2).
     */
    void execute(OutboxMessage message);
    
    /**
     * Resolución (ADR-007/008 Enmienda 1, D1 y D4): compensa en el agregado origen o, si la política lo permite, recupera
     * hacia delante. Misma clasificación de excepciones que {@link #execute}.
     */
    void compensate(OutboxMessage message);

    /**
     * Resolución manual (Enmienda 1, D5): corre en la misma transacción que el paso del mensaje a {@code RESOLVED} y el
     * registro de auditoría. Por defecto no hace nada.
     */
    default void onManualResolution(OutboxMessage message, String operator, String note) {
    }
}
