package identity.application.authorization;

/**
 * Commands subject to platform-level authorization policies (ADR-038 §2.7).
 */
public enum PlatformCommandType {
    VERIFY_ORGANIZATION,
    REJECT_ORGANIZATION,
    REQUEST_ORGANIZATION_INFORMATION,
    GRANT_PLATFORM_AUTHORITY,
    REVOKE_PLATFORM_AUTHORITY,
    /** Cola de verificación: organizaciones pendientes (autorización (3) de Carlos, §3.1). */
    READ_VERIFICATION_QUEUE,
    /** Lista de administradores de plataforma (autorización (3) de Carlos, §3.2). */
    READ_PLATFORM_ADMINISTRATORS,
    /** Logo de una organización por la ruta autenticada (ADR-051 F6, Enmienda 1; plan de la Tarea 2, P-T2-2). */
    READ_ORGANIZATION_LOGO,
    /** Leer la evidencia de entrega de cualquier organización (ADR-051 F6; plan de cierre §T5). */
    READ_DELIVERY_EVIDENCE,
    /** Retirar una foto que incumple F7: evidencia, logo o portada (ADR-051 F7; plan de cierre §T5). */
    REMOVE_MEDIA
}
