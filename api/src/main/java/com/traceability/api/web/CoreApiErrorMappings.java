package com.traceability.api.web;

import org.springframework.stereotype.Component;

import java.util.List;

/** Skeleton B6-0. */
@Component
public class CoreApiErrorMappings implements ApiErrorMappings {

    @Override
    public String module() {
        return "core";
    }

    @Override
    public List<ApiErrorMapping> mappings() {
        return List.of();
    }
}
