package com.traceability.api.application.controller;

import com.traceability.api.application.dto.PublicDonationTrackingDTO;
import com.traceability.api.application.mapper.PublicDonationMapper;
import com.traceability.api.infrastructure.security.TrackingCodeAuthFilter;
import com.traceability.core.application.port.out.DonationReadPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/donations")
public class PublicDonationController {

    private final DonationReadPort donationReadPort;
    private final PublicDonationMapper mapper;

    public PublicDonationController(DonationReadPort donationReadPort, PublicDonationMapper mapper) {
        this.donationReadPort = donationReadPort;
        this.mapper = mapper;
    }

    @GetMapping(value = "/tracking", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PublicDonationTrackingDTO> getTracking(
            @RequestAttribute(TrackingCodeAuthFilter.FUND_ID_ATTRIBUTE) String fundId) {

        return donationReadPort.findByFundId(fundId)
                .map(mapper::toPublicDTO)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
