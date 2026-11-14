package com.traceability.api.web.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** ADR-054 D3: el filtro del correlationId va el primero tras el CORS, antes del límite de intentos y del JWT. */
@Configuration
public class CorrelationIdConfig {

    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
