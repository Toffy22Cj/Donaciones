package com.traceability.app.web.tracking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.infrastructure.security.TrackingCodeAuthFilter;
import com.traceability.app.application.integrity.TrackingIntegrityService;
import com.traceability.app.application.integrity.TrackingIntegrityService.BatchIntegrity;
import com.traceability.app.application.integrity.TrackingIntegrityService.DonationIntegrity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Integridad de la donación para su donante (encargo 6, P4): con el {@code trackingCode} en la cabecera, como TR-01
 * (el filtro del seguimiento lo valida y deja el {@code fundId}). Solo lectura; nunca devuelve ids internos ni datos de
 * otras donaciones (ficha {@code ficha-p4-integridad-en-seguimiento.md}).
 */
@RestController
public class TrackingIntegrityController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerificationResponse(String result, String reason, String reasonText, Boolean affectsThisDonation) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchResponse(String anchorStatus, String merkleRoot, String transactionHash, String network,
                                String anchoredAt, Long confirmedBlockNumber, int eventsOfThisDonation,
                                VerificationResponse verification) {}

    public record IntegrityResponse(List<BatchResponse> batches, int unanchoredEvents, String checkedAt) {}

    private final TrackingIntegrityService integrity;

    public TrackingIntegrityController(TrackingIntegrityService integrity) {
        this.integrity = integrity;
    }

    @GetMapping("/api/v1/donations/tracking/integrity")
    public ResponseEntity<IntegrityResponse> integrity(
            @RequestAttribute(TrackingCodeAuthFilter.FUND_ID_ATTRIBUTE) String fundId) {
        DonationIntegrity d = integrity.integrityOf(fundId);
        List<BatchResponse> batches = d.batches().stream().map(TrackingIntegrityController::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new IntegrityResponse(batches, d.unanchoredEvents(), d.checkedAt().toString()));
    }

    private static BatchResponse toResponse(BatchIntegrity b) {
        TrackingIntegrityService.Verification v = b.verification();
        return new BatchResponse(b.anchorStatus(), b.merkleRoot(), b.transactionHash(), b.network(),
                text(b.anchoredAt()), b.confirmedBlockNumber(), b.eventsOfThisDonation(),
                new VerificationResponse(v.result().name(), v.reason() == null ? null : v.reason().name(),
                        v.reason() == null ? null : v.reason().text, v.affectsThisDonation()));
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
