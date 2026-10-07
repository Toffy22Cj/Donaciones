package com.traceability.api.application.controller;

import com.traceability.api.application.dto.NarrativeResponseDTO;
import com.traceability.api.application.mapper.PublicNarrativeMapper;
import com.traceability.api.infrastructure.security.TrackingCodeAuthFilter;
import com.traceability.contracts.NarrativeReadModel;
import com.traceability.contracts.NarrativeReadPort;
import com.traceability.contracts.NarrativeStatus;
import com.traceability.core.application.port.out.DonationReadPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/donations/tracking/narrative")
public class PublicNarrativeController {

    private final DonationReadPort donationReadPort;
    private final NarrativeReadPort narrativeReadPort;
    private final PublicNarrativeMapper narrativeMapper;

    public PublicNarrativeController(DonationReadPort donationReadPort, NarrativeReadPort narrativeReadPort, PublicNarrativeMapper narrativeMapper) {
        this.donationReadPort = donationReadPort;
        this.narrativeReadPort = narrativeReadPort;
        this.narrativeMapper = narrativeMapper;
    }

    @GetMapping
    public ResponseEntity<NarrativeResponseDTO> getNarrative(
            @RequestAttribute(TrackingCodeAuthFilter.FUND_ID_ATTRIBUTE) String fundId) {

        // Validamos si la proyección base del fondo ya existe.
        if (donationReadPort.findByFundId(fundId).isEmpty()) {
            // SECURITY/CONSISTENCY NOTE (404 vs 202):
            // Este caso (trackingCode válido, pero sin proyección en DonationReadPort) es idéntico 
            // al escenario de Tarea 3.9. Si retornáramos 202 PENDING, estaríamos dando una señal 
            // inconsistente al cliente (404 para los datos del fondo, pero 202 para su narrativa). 
            // Dado que la narrativa es un derivado de la proyección base (facts), si la base no existe, 
            // la narrativa tampoco existe. Retornar 404 preserva el contrato semántico y evita 
            // enumeración o inconsistencia de estado en los clientes.
            return ResponseEntity.notFound().build();
        }

        Optional<NarrativeReadModel> modelOpt = narrativeReadPort.getOrTriggerGeneration(fundId);
        
        if (modelOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        NarrativeReadModel model = modelOpt.get();
        NarrativeResponseDTO dto = narrativeMapper.toDto(model);

        if (model.status() == NarrativeStatus.PENDING) {
            return ResponseEntity.accepted().body(dto);
        }

        return ResponseEntity.ok(dto);
    }
}
