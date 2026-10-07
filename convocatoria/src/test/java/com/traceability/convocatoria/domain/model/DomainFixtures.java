package com.traceability.convocatoria.domain.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Fixtures de los tests de dominio. */
final class DomainFixtures {

    static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant END = Instant.parse("2026-12-31T00:00:00Z");

    private DomainFixtures() {
    }

    static ConvocatoriaConfiguration monetary(TargetPolicy policy, OnTargetReached onTargetReached,
                                              PaymentMethod... methods) {
        return new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY), Set.of(methods), "COP", 1000L,
                policy, onTargetReached);
    }

    static ConvocatoriaConfiguration flexibleMonetary() {
        return monetary(TargetPolicy.FLEXIBLE, null, PaymentMethod.GATEWAY, PaymentMethod.BANK_TRANSFER,
                PaymentMethod.CASH);
    }

    static ConvocatoriaConfiguration inKindOnly() {
        return new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND), null, null, null, null, null);
    }

    static Convocatoria convocatoria(ConvocatoriaConfiguration configuration) {
        return Convocatoria.create("camp-1", "org-1", "PUB-1", "Title", "desc", Visibility.PUBLIC, START, END,
                configuration);
    }
}
