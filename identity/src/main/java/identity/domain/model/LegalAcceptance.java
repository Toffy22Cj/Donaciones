package identity.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Aceptación de una versión de un texto legal (ADR-050 D2): la versión y el instante en que se aceptó. */
public record LegalAcceptance(String version, Instant acceptedAt) {
    public LegalAcceptance {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
