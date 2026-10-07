package com.traceability.api.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.application.security.TrackingCodeValidationResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

@Component
public class TrackingCodeAuthFilter extends OncePerRequestFilter {

    public static final String FUND_ID_ATTRIBUTE = "fundId";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TARGET_PATH_PATTERN = "/api/v1/donations/tracking/**";

    private final TrackingCodeService trackingCodeService;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher;

    public TrackingCodeAuthFilter(TrackingCodeService trackingCodeService, ObjectMapper objectMapper) {
        this.trackingCodeService = trackingCodeService;
        this.objectMapper = objectMapper;
        this.pathMatcher = new AntPathMatcher();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathMatcher.match(TARGET_PATH_PATTERN, request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            sendUnauthorized(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        TrackingCodeValidationResult result = trackingCodeService.validate(token);

        if (!result.valid()) {
            sendUnauthorized(request, response);
            return;
        }

        result.fundId().ifPresent(fundId -> request.setAttribute(FUND_ID_ATTRIBUTE, fundId));
        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/problem+json");

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid or missing tracking code");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setInstance(URI.create(request.getRequestURI()));

        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
