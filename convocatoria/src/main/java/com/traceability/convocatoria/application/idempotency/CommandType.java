package com.traceability.convocatoria.application.idempotency;

/**
 * Tipos de comando sujetos a idempotencia (Enmienda §3.5; ID; implementation_plan.md §7.1, §7.3).
 * <p>
 * Los comandos de cliente comparten un único espacio de {@code commandId}: el registro guarda el tipo para rechazar la
 * reutilización de un {@code commandId} entre tipos (I1). Los comandos de sistema tienen su propio espacio, con
 * identidad {@code (commandType, commandId)}, y su {@code commandId} se deriva de forma determinista de la entidad que
 * procesan; nunca lo aporta un cliente, así que un cliente no puede ocupar su clave de antemano (ADR-037
 * Enmienda 2 §3.3).
 */
public enum CommandType {
    CREATE_CONVOCATORIA(false),
    EDIT_CONFIGURATION(false),
    ASSIGN_EMPLOYEE_TO_CAMPAIGN(false),
    DESIGNATE_ADMINISTRATOR_AS_CAMPAIGN_RESPONSIBLE(false),
    REMOVE_RESPONSIBLE(false),
    CLOSE_CONVOCATORIA(false),
    CREATE_DONATION_INTENT(false),
    /**
     * Aplicación de fondos de una {@code DonationIntent} ya {@code CONFIRMED}; {@code commandId = intentId}. Clave
     * conceptual {@code APPLY_FUNDS:{intentId}}, guardada como {@code _id = {commandType, commandId}}.
     */
    APPLY_FUNDS(true);

    private final boolean system;

    CommandType(boolean system) {
        this.system = system;
    }

    /** Si es un comando de sistema, con espacio de claves propio y {@code commandId} derivado, nunca de cliente. */
    public boolean isSystem() {
        return system;
    }
}
