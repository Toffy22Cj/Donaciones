package com.traceability.app.application.funds;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración de la recuperación de la aplicación de fondos (ADR-045 §2.2, §2.3).
 *
 * @param enabled     scheduler de respaldo activo; {@code false} por defecto, también en producción, hasta que existan
 *                    P1, T1 y P8 (ADR-045 §2.2)
 * @param fixedDelay  espera entre ejecuciones del scheduler
 * @param initialDelay espera antes de la primera ejecución tras el arranque
 * @param batchSize   intenciones por ejecución
 * @param retryWindow ventana de reintento desde el primer intento fallido (aprobado: 4 horas, como ADR-010)
 * @param maxAttempts intentos fallidos reintentables antes de la cuarentena (valor de implementación, ADR-045 §7)
 */
@ConfigurationProperties(prefix = "convocatoria.funds-application.recovery")
public record FundsApplicationProperties(Boolean enabled, Duration fixedDelay, Duration initialDelay,
                                         Integer batchSize,
                                         Duration retryWindow, Integer maxAttempts) {

    public FundsApplicationProperties {
        enabled = enabled != null && enabled;
        fixedDelay = fixedDelay == null ? Duration.ofMinutes(1) : fixedDelay;
        initialDelay = initialDelay == null ? Duration.ofMinutes(1) : initialDelay;
        batchSize = batchSize == null ? 50 : batchSize;
        retryWindow = retryWindow == null ? Duration.ofHours(4) : retryWindow;
        maxAttempts = maxAttempts == null ? 10 : maxAttempts;
        if (batchSize <= 0 || maxAttempts <= 0 || retryWindow.isNegative() || retryWindow.isZero()) {
            throw new IllegalArgumentException("batch-size, max-attempts and retry-window must be positive");
        }
    }
}
