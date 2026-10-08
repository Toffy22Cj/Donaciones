package com.traceability.app.application.integrity;

import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.EventBatchMembershipPort;
import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Encargo 6, P4. Esqueleto. */
@Service
public class TrackingIntegrityService {

    public enum Result { MATCH, MISMATCH, INCONCLUSIVE }

    public enum Reason {
        NOT_ANCHORED("El lote aún no está anclado en la cadena"),
        LEGACY_BATCH("Lote antiguo sin las hojas guardadas: no se puede recalcular"),
        ROOT_MISMATCH("La raíz recalculada desde los eventos no coincide con la anclada"),
        LEAF_COUNT_CHANGED("Cambió el número de eventos del lote"),
        VERIFICATION_INCONCLUSIVE("La verificación no fue concluyente");

        public final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    public record Verification(Result result, Reason reason, Boolean affectsThisDonation) {}

    public record BatchIntegrity(String anchorStatus, String merkleRoot, String transactionHash, String network,
                                 Instant anchoredAt, Long confirmedBlockNumber, int eventsOfThisDonation,
                                 Verification verification) {}

    public record DonationIntegrity(List<BatchIntegrity> batches, int unanchoredEvents, Instant checkedAt) {}

    public TrackingIntegrityService(DonationReadPort donations, EventBatchMembershipPort membership,
                                    MerkleBatchRepositoryPort batches, IntegrityVerificationPort verification,
                                    ObjectProvider<Clock> clock) {
    }

    public DonationIntegrity integrityOf(String fundId) {
        throw new UnsupportedOperationException("pendiente");
    }
}
