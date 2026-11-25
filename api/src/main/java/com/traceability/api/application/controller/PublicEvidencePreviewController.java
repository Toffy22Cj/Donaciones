package com.traceability.api.application.controller;

import com.traceability.api.infrastructure.security.TrackingCodeAuthFilter;
import com.traceability.core.application.port.out.AssetAuthorizationPort;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.LogisticsReadItem;
import com.traceability.core.application.port.out.PublishedEvidencePreviewPort;
import com.traceability.core.application.security.AssetRefService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * La copia censurada de la foto de entrega, en el seguimiento público (ADR-021-D Enmienda 2, E5 a E7; ADR-051 Enmienda 2,
 * Tarea 4). Se autentica como el resto del seguimiento: el {@code trackingCode} solo en la cabecera
 * {@code Authorization: Bearer} ({@link TrackingCodeAuthFilter}); un código en la consulta nunca lo sustituye (E6).
 *
 * <ul>
 *   <li>Sin código o inválido → 401 (lo responde el filtro).</li>
 *   <li>Código válido que no cubre el {@code assetRef} → el <b>mismo</b> 401 que el historial (nunca 404: no se puede
 *   enumerar activos).</li>
 *   <li>Cubre el activo pero no hay copia publicada, la original está retirada o no hay foto → el mismo 404 con el mismo
 *   cuerpo en los tres casos (E7): el puerto no distingue y esta clase tampoco.</li>
 * </ul>
 * Responde el PNG con {@code Cache-Control: private, no-store}. Nunca la original ni metadato alguno.
 */
@RestController
@RequestMapping("/api/v1/donations/tracking/assets")
public class PublicEvidencePreviewController {

    private final DonationReadPort donationReadPort;
    private final AssetRefService assetRefService;
    private final AssetAuthorizationPort assetAuthorizationPort;
    private final PublishedEvidencePreviewPort previews;

    public PublicEvidencePreviewController(DonationReadPort donationReadPort, AssetRefService assetRefService,
                                           AssetAuthorizationPort assetAuthorizationPort,
                                           PublishedEvidencePreviewPort previews) {
        this.donationReadPort = donationReadPort;
        this.assetRefService = assetRefService;
        this.assetAuthorizationPort = assetAuthorizationPort;
        this.previews = previews;
    }

    @GetMapping("/{assetRef}/evidence-preview")
    public ResponseEntity<?> getPreview(
            @PathVariable("assetRef") String assetRef,
            @RequestAttribute(TrackingCodeAuthFilter.FUND_ID_ATTRIBUTE) String fundId) {

        // Misma resolución y mismo 401 uniforme que PublicAssetHistoryController (ver su nota de seguridad: 401 y no 404,
        // para que nadie distinga «no existe» de «no es suyo»).
        Optional<DonationReadModel> model = donationReadPort.findByFundId(fundId);
        if (model.isEmpty()) {
            return unauthorized();
        }
        Collection<String> candidates = model.get().logistics().stream()
                .map(LogisticsReadItem::assetId).collect(Collectors.toSet());
        Optional<String> assetId = assetRefService.resolveAssetId(assetRef, candidates);
        if (assetId.isEmpty() || !assetAuthorizationPort.assetBelongsToFund(assetId.get(), fundId)) {
            return unauthorized();
        }

        byte[] png = previews.findPublishedPreview(assetId.get()).orElseThrow(TrackedResourceNotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    private static ResponseEntity<ProblemDetail> unauthorized() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid or missing token");
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }
}
