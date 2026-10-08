package com.traceability.core.application.integrity;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.port.out.StoredEventReadPort;
import com.traceability.core.application.port.out.StoredEventReadPort.StoredEvent;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Recalcula el {@code eventHash} de cada evento con el <b>mismo</b> pipeline de producción
 * ({@link EventCanonicalMapper#toCanonicalMap} y {@link HashPort#canonicalizeAndHash} con el {@code previousHash}
 * guardado) y comprueba la cadena {@code previousHash} desde el evento anterior a la cobertura; la génesis es la
 * secuencia 1, con {@code previousHash = GENESIS}. No cambia la canonicalización, el hash, el Merkle ni el anclaje.
 * <p>
 * Forma canónica (Enmienda 1 de ADR-039 §2.2.3, aprobada por Carlos el 2026-10-08): todo evento se valida con la forma
 * actual. Solo si su {@code recordedAt} es anterior al corte de {@code 0579f41} ({@link #LEGACY_FORM_CUTOFF}) se prueba
 * además la forma anterior, con {@code actorRef} como texto ({@link LegacyCanonicalForm}); si tampoco coincide, el
 * evento es {@code CANONICAL_FORM_UNKNOWN} (la verificación será {@code INCONCLUSIVE}, nunca {@code MISMATCH}). Un
 * evento posterior al corte solo admite la forma actual.
 */
@Component
public class RecomputingEventIntegrityChecker implements EventIntegrityChecker {

    /** Fecha del commit {@code 0579f41} (2026-09-16 21:49:04 -0500), que sacó {@code actorRef} del hash. */
    public static final Instant LEGACY_FORM_CUTOFF = Instant.parse("2026-09-17T02:49:04Z");

    private final StoredEventReadPort events;
    private final EventCanonicalMapper mapper;
    private final HashPort hash;

    public RecomputingEventIntegrityChecker(StoredEventReadPort events, EventCanonicalMapper mapper, HashPort hash) {
        this.events = events;
        this.mapper = mapper;
        this.hash = hash;
    }

    @Override
    public List<Finding> check(Map<String, SequenceRange> coverage) {
        List<Finding> findings = new ArrayList<>();
        String stream = null;
        String expectedPrevious = null;
        for (StoredEvent e : events.findByCoverage(coverage)) {
            if (!e.streamId().equals(stream)) {
                stream = e.streamId();
                expectedPrevious = previousOf(e);
            }
            if (!Objects.equals(e.previousHash(), expectedPrevious)) {
                findings.add(new Finding(e.streamId(), e.sequence(), Problem.CHAIN_BROKEN));
            }
            expectedPrevious = e.eventHash();
            Problem form = formProblem(e);
            if (form != null) {
                findings.add(new Finding(e.streamId(), e.sequence(), form));
            }
        }
        return findings;
    }

    /** {@code GENESIS} para la secuencia 1; si no, el {@code eventHash} guardado del evento anterior (o null). */
    private String previousOf(StoredEvent first) {
        if (first.sequence() == 1) {
            return DomainEvent.GENESIS_HASH;
        }
        return events.find(first.streamId(), first.sequence() - 1).map(StoredEvent::eventHash).orElse(null);
    }

    private Problem formProblem(StoredEvent e) {
        Optional<Map<String, Object>> current = canonicalMap(e);
        if (current.isPresent() && Objects.equals(hash.canonicalizeAndHash(current.get(), e.previousHash()), e.eventHash())) {
            return null;
        }
        if (!beforeCutoff(e)) {
            return Problem.HASH_MISMATCH;
        }
        if (current.isPresent() && Objects.equals(hash.canonicalizeAndHash(
                LegacyCanonicalForm.of(current.get(), e.legacyActorRef()), e.previousHash()), e.eventHash())) {
            return null;
        }
        return Problem.CANONICAL_FORM_UNKNOWN;
    }

    private static boolean beforeCutoff(StoredEvent e) {
        try {
            return e.recordedAt() != null && Instant.parse(e.recordedAt()).isBefore(LEGACY_FORM_CUTOFF);
        } catch (RuntimeException unparseable) {
            return false;
        }
    }

    /** El mapa canónico actual del evento guardado; vacío si el payload ya no se puede leer con su tipo y versión. */
    private Optional<Map<String, Object>> canonicalMap(StoredEvent e) {
        try {
            DomainEventPayload payload = mapper.convertPayload(new HashMap<>(e.payload()), e.eventType(), e.schemaVersion());
            return Optional.of(mapper.toCanonicalMap(e.eventId(), e.streamId(), e.aggregateType(), e.sequence(),
                    e.eventType(), e.schemaVersion(), e.occurredAt() == null ? null : Instant.parse(e.occurredAt()),
                    e.recordedAt() == null ? null : Instant.parse(e.recordedAt()), e.origin(), payload));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
