package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.NarrativeResponseDTO;
import com.traceability.contracts.NarrativeReadModel;
import org.springframework.stereotype.Component;

@Component
public class PublicNarrativeMapper {

    public NarrativeResponseDTO toDto(NarrativeReadModel model) {
        if (model == null) {
            return null;
        }
        return new NarrativeResponseDTO(
                model.status() != null ? model.status().name() : null,
                model.content(),
                model.source() != null ? model.source().name() : null
        );
    }
}
