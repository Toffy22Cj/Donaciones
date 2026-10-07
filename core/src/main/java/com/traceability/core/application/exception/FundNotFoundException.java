package com.traceability.core.application.exception;

/**
 * El fondo no existe (plan P1.1, DD-30). Hacia fuera responde igual que "es de otra organización" (como DD-12): la
 * respuesta no revela qué ids existen. Es un caso de {@code FundNotAssociatedToOrganizationException}, que es lo que
 * el código lanzaba antes para un fondo sin stream.
 */
public class FundNotFoundException extends com.traceability.core.domain.fund.exceptions.FundNotAssociatedToOrganizationException {
    public FundNotFoundException(String fundId) {
        super("Fund " + fundId + " does not exist");
    }
}
