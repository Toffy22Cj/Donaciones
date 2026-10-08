package com.traceability.convocatoria.domain.model;

/** Nombres de proveedor de pago (Enmienda 3 de ADR-037, D2). Solo existe el simulado: no hay proveedor real (P1). */
public final class PaymentProviders {

    /** Proveedor de la demo. Se rechaza fuera de {@code dev}/{@code demo} (E3-Q1, dos barreras). */
    public static final String SIMULATED = "SIMULATED";

    private PaymentProviders() {}
}
