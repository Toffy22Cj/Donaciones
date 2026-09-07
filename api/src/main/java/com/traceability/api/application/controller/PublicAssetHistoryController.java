package com.traceability.api.application.controller;

import com.traceability.api.application.dto.AssetHistoryPublicDTO;
import com.traceability.api.application.mapper.AssetHistoryMapper;
import com.traceability.api.infrastructure.security.TrackingCodeAuthFilter;
import com.traceability.core.application.port.out.AssetAuthorizationPort;
import com.traceability.core.application.port.out.AssetHistoryReadPort;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.LogisticsReadItem;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/v1/donations/tracking/assets")
public class PublicAssetHistoryController {

    private final DonationReadPort donationReadPort;
    private final AssetRefService assetRefService;
    private final AssetAuthorizationPort assetAuthorizationPort;
    private final AssetHistoryReadPort assetHistoryReadPort;
    private final AssetHistoryMapper assetHistoryMapper;

    public PublicAssetHistoryController(
            DonationReadPort donationReadPort,
            AssetRefService assetRefService,
            AssetAuthorizationPort assetAuthorizationPort,
            AssetHistoryReadPort assetHistoryReadPort,
            AssetHistoryMapper assetHistoryMapper) {
        this.donationReadPort = donationReadPort;
        this.assetRefService = assetRefService;
        this.assetAuthorizationPort = assetAuthorizationPort;
        this.assetHistoryReadPort = assetHistoryReadPort;
        this.assetHistoryMapper = assetHistoryMapper;
    }

    @GetMapping("/{assetRef}/history")
    public ResponseEntity<?> getAssetHistory(
            @PathVariable("assetRef") String assetRef,
            @RequestAttribute(TrackingCodeAuthFilter.FUND_ID_ATTRIBUTE) String fundId) {
        
        Optional<DonationReadModel> modelOpt = donationReadPort.findByFundId(fundId);
        if (modelOpt.isEmpty()) {
            // SECURITY NOTE (401 vs 404):
            // In Tarea 3.9, a missing projection for a valid fundId returned a 404 
            // (eventual consistency) because the fundId was already cryptographically guaranteed.
            // Here, we return a 401. If we returned 404 for "missing projection" and 401 
            // for "invalid/unowned assetRef", an attacker could enumerate the existence 
            // of funds by distinguishing between the two errors. Both cases are collapsed 
            // into a single 401 to prevent IDOR/enumeration.
            return buildUnauthorizedResponse();
        }

        DonationReadModel model = modelOpt.get();
        Collection<String> candidateAssetIds = model.logistics().stream()
                .map(LogisticsReadItem::assetId)
                .collect(Collectors.toSet());

        Optional<String> resolvedAssetId = assetRefService.resolveAssetId(assetRef, candidateAssetIds);
        if (resolvedAssetId.isEmpty()) {
            return buildUnauthorizedResponse();
        }

        String assetId = resolvedAssetId.get();

        if (!assetAuthorizationPort.assetBelongsToFund(assetId, fundId)) {
            return buildUnauthorizedResponse();
        }

        return assetHistoryReadPort.getHistory(assetId)
                .map(assetHistoryMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build()); // In case history projection is missing, this is just a normal 404.
    }

    private ResponseEntity<ProblemDetail> buildUnauthorizedResponse() {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid or missing token");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problemDetail);
    }
}
