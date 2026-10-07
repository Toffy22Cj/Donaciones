package com.traceability.convocatoria.domain.model;

/**
 * Fuente de confirmación, distinta de `PaymentMethod` (N5, N7, Enmienda §5.1–§5.2).
 */
public enum ConfirmationSource {
    PAYMENT_PROVIDER,
    ORGANIZATION
}
