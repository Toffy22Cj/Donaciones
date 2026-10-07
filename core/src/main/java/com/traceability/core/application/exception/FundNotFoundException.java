package com.traceability.core.application.exception;

/**
 * El fondo no existe (plan P1.1, DD-30). Independiente de
 * {@link com.traceability.core.domain.fund.exceptions.FundNotAssociatedToOrganizationException} (encargo 3, punto 5,
 * Carlos, 2026-10-07): las dos responden el mismo 403 que "es de otra organización", para no revelar qué ids existen.
 */
public class FundNotFoundException extends RuntimeException {
    public FundNotFoundException(String fundId) {
        super("Fund " + fundId + " does not exist");
    }
}
