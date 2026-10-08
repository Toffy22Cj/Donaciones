package com.traceability.convocatoria.application.audit;

/**
 * Acciones registradas en el audit log del módulo (ADR-037 §2, §6; implementation_plan.md §4.4).
 */
public enum ConvocatoriaAuditAction {
    CONVOCATORIA_CREATED,
    CONFIGURATION_EDITED,
    EMPLOYEE_ASSIGNED,
    ADMINISTRATOR_DESIGNATED,
    RESPONSIBLE_REMOVED,
    CONVOCATORIA_CLOSED,
    DONATION_INTENT_CREATED,
    /** Salida manual de la cuarentena de aplicación de fondos (ADR-045 §2.3). */
    APPLICATION_QUARANTINE_RELEASED,
    /** Enmienda 4 de ADR-037: solicitud de cambio de configuración y su decisión. */
    CONFIGURATION_CHANGE_REQUESTED,
    CONFIGURATION_CHANGE_APPROVED,
    CONFIGURATION_CHANGE_REJECTED
}
