package com.traceability.api.web.ratelimit;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ADR-053: composición del límite de intentos. El filtro se registra con un orden explícito justo detrás del CORS
 * ({@code HIGHEST_PRECEDENCE}, {@code CorsConfig}) y por delante de {@code JwtAuthFilter} y
 * {@code TrackingCodeAuthFilter}, que no declaran orden y quedan en {@code LOWEST_PRECEDENCE}.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /** Orden del filtro: después de CORS, antes de cualquier autenticación. */
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private final RateLimitProperties properties;

    public RateLimitConfig(RateLimitProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    @Bean
    public SlidingWindowRateLimiter slidingWindowRateLimiter(ObjectProvider<Clock> clock) {
        return new SlidingWindowRateLimiter(clock.getIfAvailable(Clock::systemUTC), properties.getMaxKeys());
    }

    @Bean
    public ClientIpResolver rateLimitClientIpResolver() {
        return new ClientIpResolver(properties.getClientIp().getTrustedProxies(), properties.getClientIp().getHeader());
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(SlidingWindowRateLimiter limiter,
                                                                   ClientIpResolver rateLimitClientIpResolver,
                                                                   ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(properties, limiter, rateLimitClientIpResolver, objectMapper));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(FILTER_ORDER);
        return registration;
    }

    @Bean
    public RateLimitSweeper rateLimitSweeper(SlidingWindowRateLimiter limiter) {
        return new RateLimitSweeper(limiter);
    }

    /** ADR-053 D5: limpieza periódica de las claves sin peticiones dentro de su ventana. */
    public static class RateLimitSweeper {
        private final SlidingWindowRateLimiter limiter;

        RateLimitSweeper(SlidingWindowRateLimiter limiter) {
            this.limiter = limiter;
        }

        @Scheduled(fixedDelayString = "${traceability.rate-limit.sweep-interval:PT1M}")
        public void sweep() {
            limiter.sweep();
        }
    }
}
