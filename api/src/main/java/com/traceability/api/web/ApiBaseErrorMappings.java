package com.traceability.api.web;

import org.springframework.stereotype.Component;

import java.util.List;

/** Skeleton B6-0. */
@Component
public class ApiBaseErrorMappings implements ApiErrorMappings {

    @Override
    public String module() {
        return "api";
    }

    @Override
    public List<ApiErrorMapping> mappings() {
        return List.of();
    }
}
