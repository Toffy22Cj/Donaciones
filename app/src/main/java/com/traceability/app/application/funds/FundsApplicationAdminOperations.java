package com.traceability.app.application.funds;

import com.traceability.convocatoria.application.service.FundsApplicationRecoveryService;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedOperationParameter;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

/**
 * Salida manual de la cuarentena de aplicación de fondos por JMX (ADR-045 §2.3; mismo patrón que
 * {@code BlockchainAdminOperationsService}, ADR-022). Nunca automática; queda en el audit log de {@code convocatoria}.
 */
@Component
@ManagedResource(objectName = "com.traceability.app.application.funds:type=FundsApplicationAdminOperations",
        description = "Admin operations for the funds application recovery (ADR-045)")
public class FundsApplicationAdminOperations {

    private final FundsApplicationRecoveryService recovery;

    public FundsApplicationAdminOperations(FundsApplicationRecoveryService recovery) {
        this.recovery = recovery;
    }

    @ManagedOperation(description = "Releases a DonationIntent from the funds application quarantine after fixing the cause")
    @ManagedOperationParameter(name = "intentId", description = "The quarantined DonationIntent")
    @ManagedOperationParameter(name = "operator", description = "Who releases it (recorded in the audit log)")
    @ManagedOperationParameter(name = "reason", description = "Why it can be retried (recorded in the audit log)")
    public boolean releaseApplicationQuarantine(String intentId, String operator, String reason) {
        return recovery.releaseApplicationQuarantine(intentId, operator, reason);
    }
}
