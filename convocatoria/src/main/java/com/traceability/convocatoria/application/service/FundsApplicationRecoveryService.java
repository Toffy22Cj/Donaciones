package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.domain.exception.DonationIntentNotFoundException;
import com.traceability.convocatoria.domain.model.DonationIntent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Parte de {@code convocatoria} de la recuperación de la aplicación de fondos (ADR-045 §2.3, §2.6): registro de
 * intentos fallidos, cuarentena, su salida manual auditada y las métricas del scheduler. La clasificación de las
 * excepciones y la política de cuarentena por agotamiento ({@code max-attempts}, {@code retry-window}) viven en el
 * orquestador de {@code app}; aquí solo se escriben los datos de operación, siempre condicionados a que la intención
 * siga {@code CONFIRMED} y sin aplicar.
 */
@Service
public class FundsApplicationRecoveryService {

    private final DonationIntentRepositoryPort donationIntents;
    private final ConvocatoriaAuditLogPort auditLog;
    private final ConvocatoriaTransactionRetryHelper transactionRetryHelper;
    private final Clock clock;

    public FundsApplicationRecoveryService(DonationIntentRepositoryPort donationIntents,
                                           ConvocatoriaAuditLogPort auditLog,
                                           ConvocatoriaTransactionRetryHelper transactionRetryHelper,
                                           ObjectProvider<Clock> clock) {
        this.donationIntents = donationIntents;
        this.auditLog = auditLog;
        this.transactionRetryHelper = transactionRetryHelper;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Registra un intento fallido en una escritura propia, después del rollback de la transacción de aplicación
     * (ADR-045 §2.3). {@code errorClass} es el nombre de la clase de la excepción, sin mensaje. Si la escritura
     * falla, el intento no queda contado y el siguiente ciclo lo repite (la barrera evita efectos dobles).
     *
     * @return el seguimiento resultante, o vacío si la intención ya no está {@code CONFIRMED} sin aplicar
     */
    public Optional<DonationIntent.ApplicationTracking> recordApplicationFailure(String intentId, String errorClass,
                                                                                 boolean quarantine) {
        return donationIntents.recordApplicationFailure(intentId, Objects.requireNonNull(errorClass, "errorClass"),
                clock.instant(), quarantine);
    }

    /** Cuarentena por agotamiento ({@code max-attempts} o {@code retry-window}, ADR-045 §2.3). */
    public boolean quarantineApplication(String intentId) {
        return donationIntents.quarantineApplication(intentId);
    }

    /**
     * Salida manual de la cuarentena (ADR-045 §2.3, patrón ADR-022): condicional ({@code CONFIRMED}, sin aplicar, en
     * cuarentena), reinicia los contadores y deja la entrada en el audit log del módulo en la misma transacción.
     *
     * @return si la intención salió de la cuarentena en esta llamada
     */
    public boolean releaseApplicationQuarantine(String intentId, String operator, String reason) {
        requireText(operator, "operator");
        requireText(reason, "reason");
        return transactionRetryHelper.executeWithRetry(() -> {
            DonationIntent intent = donationIntents.findById(intentId).orElseThrow(
                    () -> new DonationIntentNotFoundException("DonationIntent " + intentId + " not found"));
            if (!donationIntents.releaseApplicationQuarantine(intentId)) {
                return false;
            }
            Instant now = clock.instant();
            auditLog.append(new ConvocatoriaAuditEntry(UUID.randomUUID().toString(),
                    ConvocatoriaAuditAction.APPLICATION_QUARANTINE_RELEASED, intent.getCampaignRef(), operator,
                    intentId, false, null, now, Map.of(
                            "reason", reason,
                            "previousAttempts", String.valueOf(intent.getApplicationTracking().attempts()),
                            "previousError", String.valueOf(intent.getApplicationTracking().lastError()))));
            return true;
        });
    }

    /** Métricas de cada ejecución del scheduler (ADR-045 §2.6). */
    public RecoveryMetrics metrics() {
        return new RecoveryMetrics(donationIntents.countPendingExcludedByCloseOnTargetClose(),
                donationIntents.countApplicationQuarantined(),
                donationIntents.oldestPendingApplicationConfirmedAt().orElse(null));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /**
     * @param excludedByCloseOnTargetClose intenciones fuera de la cola por P9
     * @param quarantined                  intenciones en cuarentena
     * @param oldestPendingConfirmedAt     confirmación de la recuperable más antigua, o {@code null} si no hay
     */
    public record RecoveryMetrics(long excludedByCloseOnTargetClose, long quarantined, Instant oldestPendingConfirmedAt) {
    }
}
