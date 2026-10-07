package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.port.out.UnacceptablePaymentEventPort;
import com.traceability.convocatoria.application.port.out.UnacceptablePaymentEventPort.UnacceptablePaymentEvent;
import com.traceability.convocatoria.domain.exception.PaymentCorrelationNotFoundException;
import com.traceability.convocatoria.domain.exception.PaymentEventMismatchException;
import com.traceability.convocatoria.domain.exception.SimulatedPaymentsNotAllowedException;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.PaymentProviders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Eventos del proveedor de pago sobre una intención {@code GATEWAY} (Enmienda 3 de ADR-037, D1, D2 y D4). La
 * confirmación usa la misma barrera de estado que la manual ({@code PENDING}, escritura condicional) y no aplica
 * fondos: eso lo hace {@code app} con el orquestador de ADR-045 cuando el resultado es {@link Outcome#CONFIRMED}.
 *
 * <p>Los logs llevan solo el {@code intentId} y el proveedor: nunca el {@code donorRef} ni la sesión.
 */
@Service
public class GatewayPaymentService {

    private static final Logger log = LoggerFactory.getLogger(GatewayPaymentService.class);

    /** Resultado de un evento de confirmación. */
    public enum Outcome {
        /** Esta llamada pasó la intención a {@code CONFIRMED}: hay que aplicar los fondos. */
        CONFIRMED,
        /** Evento repetido o intención ya confirmada (o posterior): 200 sin efecto. */
        DUPLICATE,
        /** Dinero recibido para una intención {@code FAILED} o {@code EXPIRED_UNKNOWN}: registrado, sin cambio de estado. */
        UNACCEPTABLE
    }

    /** Resultado de un evento de pago fallido. */
    public enum FailureOutcome { FAILED, ALREADY_FAILED, IGNORED }

    private final DonationIntentRepositoryPort donationIntents;
    private final UnacceptablePaymentEventPort unacceptablePayments;
    private final boolean allowSimulatedPayments;
    private final Clock clock;
    private final AtomicLong unacceptableCounter = new AtomicLong();

    public GatewayPaymentService(DonationIntentRepositoryPort donationIntents,
                                 UnacceptablePaymentEventPort unacceptablePayments,
                                 @Value("${traceability.demo.simulated-payments:false}") boolean allowSimulatedPayments,
                                 ObjectProvider<Clock> clock) {
        this.donationIntents = donationIntents;
        this.unacceptablePayments = unacceptablePayments;
        this.allowSimulatedPayments = allowSimulatedPayments;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** Segunda barrera de E3-Q1: el proveedor simulado se rechaza con la política desactivada, venga de donde venga. */
    public void requireProviderAllowed(String paymentProvider) {
        // B6-b
    }

    public Outcome confirmGatewayPayment(String paymentProvider, String paymentSessionId, String providerEventId,
                                         long amount, String currency) {
        throw new UnsupportedOperationException("B6-b");
    }

    public FailureOutcome failGatewayPayment(String paymentProvider, String paymentSessionId, String providerEventId) {
        throw new UnsupportedOperationException("B6-b");
    }

    /** Contador de confirmaciones no aceptables desde el arranque (D4), expuesto por JMX en {@code app}. */
    public long unacceptablePaymentEventsSinceStart() {
        return unacceptableCounter.get();
    }

    /** Total persistido de confirmaciones no aceptables (D4). */
    public long unacceptablePaymentEventsRecorded() {
        return unacceptablePayments.count();
    }

    private DonationIntent correlate(String paymentProvider, String paymentSessionId, String providerEventId) {
        Objects.requireNonNull(paymentProvider, "paymentProvider");
        if (paymentSessionId == null || providerEventId == null) {
            throw new PaymentCorrelationNotFoundException("paymentSessionId and providerEventId are required");
        }
        requireProviderAllowed(paymentProvider);
        DonationIntent intent = donationIntents.findByPaymentSessionId(paymentSessionId).orElseThrow(
                () -> new PaymentCorrelationNotFoundException("No DonationIntent for the payment session"));
        if (!paymentProvider.equals(intent.getPaymentProvider())) {
            throw new PaymentEventMismatchException("Payment provider does not match DonationIntent " + intent.getIntentId());
        }
        return intent;
    }

    private Outcome unacceptable(DonationIntent intent, String paymentProvider, String providerEventId, Instant now) {
        unacceptablePayments.record(new UnacceptablePaymentEvent(paymentProvider, providerEventId, intent.getIntentId(),
                intent.getAmount(), intent.getCurrency(), "CONFIRMED_ON_" + intent.getStatus().name(), now));
        unacceptableCounter.incrementAndGet();
        // Dinero recibido para una intención no pendiente: queda en unacceptable_payment_events hasta P1 (D4)
        log.error("Unacceptable payment event: DonationIntent {} is {} (provider {}); pending resolution P1",
                intent.getIntentId(), intent.getStatus(), paymentProvider);
        return Outcome.UNACCEPTABLE;
    }
}
