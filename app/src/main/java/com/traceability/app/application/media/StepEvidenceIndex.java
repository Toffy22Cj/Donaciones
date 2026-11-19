package com.traceability.app.application.media;

import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV4Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV5Payload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Las fotos de los pasos de un activo (ADR-051 Enmienda 2, E2-6; plan de la Tarea 2, §2.3), leídas del <b>evento</b> de
 * cada paso y no del estado del agregado, que solo recuerda la foto de la entrega (H-7). Un paso repetido (re-despacho)
 * da una entrada por evento, cada una con su {@code sequence} (la posición del evento en el flujo, desde 1). Las
 * versiones anteriores de los eventos no tenían foto, y una huella con formato inválido (un texto libre heredado de la
 * entrega) cuenta como «sin foto».
 */
public final class StepEvidenceIndex {

    private static final Pattern FOOTPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private StepEvidenceIndex() {}

    /** Los cuatro pasos con foto. {@code routeName} es el de la ruta (minúsculas); la entrega usa {@code DELIVERY_EVIDENCE}. */
    public enum Step {
        REGISTERED(MediaPurpose.REGISTRATION_EVIDENCE),
        DISPATCHED(MediaPurpose.DISPATCH_EVIDENCE),
        RECEIVED(MediaPurpose.RECEIPT_EVIDENCE),
        DELIVERED(MediaPurpose.DELIVERY_EVIDENCE);

        private final MediaPurpose purpose;

        Step(MediaPurpose purpose) {
            this.purpose = purpose;
        }

        public MediaPurpose purpose() {
            return purpose;
        }

        public String routeName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** El paso de una ruta; solo la forma exacta en minúsculas. {@code steps} y cualquier otro texto no son un paso. */
        public static Optional<Step> fromRoute(String value) {
            for (Step step : values()) {
                if (step.routeName().equals(value)) {
                    return Optional.of(step);
                }
            }
            return Optional.empty();
        }
    }

    /** {@code evidenceRef} es nulo si ese evento no tiene foto. */
    public record Entry(Step step, long sequence, Instant occurredAt, String evidenceRef) {}

    public static List<Entry> of(List<DomainEvent> events) {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            DomainEvent event = events.get(i);
            Step step = stepOf(event.payload());
            if (step != null) {
                out.add(new Entry(step, i + 1L, event.occurredAt(), evidenceRefOf(event.payload())));
            }
        }
        return out;
    }

    /** La entrada del último evento de ese paso (la de mayor {@code sequence}). */
    public static Optional<Entry> latest(List<Entry> entries, Step step) {
        Entry latest = null;
        for (Entry entry : entries) {
            if (entry.step() == step && (latest == null || entry.sequence() > latest.sequence())) {
                latest = entry;
            }
        }
        return Optional.ofNullable(latest);
    }

    /** La entrada de ese evento, solo si es de ese paso. */
    public static Optional<Entry> at(List<Entry> entries, Step step, long sequence) {
        return entries.stream().filter(e -> e.step() == step && e.sequence() == sequence).findFirst();
    }

    private static Step stepOf(DomainEventPayload payload) {
        return switch (payload) {
            case AssetRegisteredPayload p -> Step.REGISTERED;
            case AssetRegisteredV2Payload p -> Step.REGISTERED;
            case AssetRegisteredV3Payload p -> Step.REGISTERED;
            case AssetRegisteredV4Payload p -> Step.REGISTERED;
            case AssetRegisteredV5Payload p -> Step.REGISTERED;
            case AssetDispatchedPayload p -> Step.DISPATCHED;
            case AssetDispatchedV2Payload p -> Step.DISPATCHED;
            case AssetDispatchedV3Payload p -> Step.DISPATCHED;
            case AssetReceivedPayload p -> Step.RECEIVED;
            case AssetReceivedV2Payload p -> Step.RECEIVED;
            case AssetReceivedV3Payload p -> Step.RECEIVED;
            case AssetDeliveredPayload p -> Step.DELIVERED;
            case AssetDeliveredV2Payload p -> Step.DELIVERED;
            default -> null;
        };
    }

    private static String evidenceRefOf(DomainEventPayload payload) {
        String ref = switch (payload) {
            case AssetRegisteredV5Payload p -> p.evidenceRef();
            case AssetDispatchedV3Payload p -> p.evidenceRef();
            case AssetReceivedV3Payload p -> p.evidenceRef();
            case AssetDeliveredPayload p -> p.evidenceRef();
            case AssetDeliveredV2Payload p -> p.evidenceRef();
            default -> null;
        };
        return ref != null && FOOTPRINT.matcher(ref).matches() ? ref : null;
    }
}
