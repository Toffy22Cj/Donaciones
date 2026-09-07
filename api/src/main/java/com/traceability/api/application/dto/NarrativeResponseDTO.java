package com.traceability.api.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NarrativeResponseDTO(
        String status,
        String content,
        String source
) {}
