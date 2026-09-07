package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.NarrativeResponseDTO;
import com.traceability.contracts.NarrativeReadModel;
import com.traceability.contracts.NarrativeSource;
import com.traceability.contracts.NarrativeStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PublicNarrativeMapperTest {

    private final PublicNarrativeMapper mapper = new PublicNarrativeMapper();

    @Test
    void shouldMapAvailableStatus() {
        NarrativeReadModel model = new NarrativeReadModel(NarrativeStatus.AVAILABLE, "Test content", NarrativeSource.LLM_GENERATED);
        NarrativeResponseDTO dto = mapper.toDto(model);
        
        assertEquals("AVAILABLE", dto.status());
        assertEquals("Test content", dto.content());
        assertEquals("LLM_GENERATED", dto.source());
    }

    @Test
    void shouldMapPendingStatus() {
        NarrativeReadModel model = new NarrativeReadModel(NarrativeStatus.PENDING, null, null);
        NarrativeResponseDTO dto = mapper.toDto(model);
        
        assertEquals("PENDING", dto.status());
        assertNull(dto.content());
        assertNull(dto.source());
    }

    @Test
    void shouldHandleNull() {
        assertNull(mapper.toDto(null));
    }
}
