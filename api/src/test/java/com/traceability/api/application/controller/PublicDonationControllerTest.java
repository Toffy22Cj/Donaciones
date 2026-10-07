package com.traceability.api.application.controller;

import com.traceability.api.application.dto.PublicDonationStatus;
import com.traceability.api.application.dto.PublicDonationTrackingDTO;
import com.traceability.api.application.dto.PublicFinancialSnapshotDTO;
import com.traceability.api.application.mapper.PublicDonationMapper;
import com.traceability.api.infrastructure.security.TrackingCodeAuthFilter;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.DonationReadPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.context.ContextConfiguration;

@WebMvcTest(controllers = PublicDonationController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@ContextConfiguration(classes = PublicDonationController.class)
class PublicDonationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DonationReadPort donationReadPort;

    @MockitoBean
    private PublicDonationMapper mapper;

    @Test
    void shouldReturn200AndDtoWhenFundIdExists() throws Exception {
        String fundId = "fund-123";
        DonationReadModel mockModel = new DonationReadModel(fundId, "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of());
        
        PublicFinancialSnapshotDTO financial = new PublicFinancialSnapshotDTO("USD", 100L, 100L, 0L, 0L, 0L);
        PublicDonationTrackingDTO mockDto = new PublicDonationTrackingDTO(financial, "camp-1", List.of(), PublicDonationStatus.ACTIVA);

        when(donationReadPort.findByFundId(fundId)).thenReturn(Optional.of(mockModel));
        when(mapper.toPublicDTO(mockModel)).thenReturn(mockDto);

        mockMvc.perform(get("/api/v1/donations/tracking")
                .requestAttr(TrackingCodeAuthFilter.FUND_ID_ATTRIBUTE, fundId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignRef").value("camp-1"))
                .andExpect(jsonPath("$.status").value("ACTIVA"))
                .andExpect(jsonPath("$.financialSnapshot.currency").value("USD"));
    }

    @Test
    void shouldReturn404WhenFundIdDoesNotExist() throws Exception {
        String fundId = "fund-404";
        
        when(donationReadPort.findByFundId(fundId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/donations/tracking")
                .requestAttr(TrackingCodeAuthFilter.FUND_ID_ATTRIBUTE, fundId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$").doesNotExist());
    }
}
