package com.traceability.api.auth.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Skeleton B3. */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "authorizationPrincipal";

    public JwtAuthFilter(JwtTokenVerifier verifier, IdentityPrincipalPort identityPrincipalPort, ObjectMapper objectMapper) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
    }
}
