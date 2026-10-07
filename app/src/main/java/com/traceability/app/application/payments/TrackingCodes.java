package com.traceability.app.application.payments;

import com.traceability.core.application.security.TrackingCodeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Deriva el {@code trackingCode} de una donación (Enmienda 3 de ADR-037, D6): solo con los fondos aplicados, con
 * {@code expiry = fundsAppliedAt + traceability.tracking-code.ttl} (E3-Q3: un año). El mismo {@code fundId} y el mismo
 * {@code fundsAppliedAt} dan siempre el mismo código; no se guarda en ningún sitio.
 */
@Component
public class TrackingCodes {

    private final TrackingCodeService trackingCodeService;
    private final Duration ttl;

    public TrackingCodes(TrackingCodeService trackingCodeService,
                         @Value("${traceability.tracking-code.ttl:P365D}") Duration ttl) {
        this.trackingCodeService = trackingCodeService;
        this.ttl = ttl;
    }

    public Optional<String> forAppliedFunds(String status, String fundId, Instant fundsAppliedAt) {
        if (!"CONFIRMED".equals(status) || fundId == null || fundsAppliedAt == null) {
            return Optional.empty();
        }
        return Optional.of(trackingCodeService.generate(fundId, fundsAppliedAt.plus(ttl)));
    }
}
