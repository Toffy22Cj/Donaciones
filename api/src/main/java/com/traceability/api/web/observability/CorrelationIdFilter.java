package com.traceability.api.web.observability;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import com.traceability.api.web.ApiExceptionHandler;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ADR-054 D3: un {@code correlationId} nuevo por petición en el MDC, de modo que todos los logs de la petición (los del
 * controlador, el del evento escrito y, si hay un 500, el de {@code ApiExceptionHandler}, que lo devuelve en el
 * cuerpo) lo compartan. Siempre lo genera el servidor: uno que venga en la petición no se acepta, para que nadie pueda
 * inyectar valores en los logs.
 * <p>
 * Al terminar deja una línea de acceso: método, <b>patrón</b> de la ruta (sin los identificadores de la URL), estado y
 * duración. Nunca la URL real, la consulta, las cabeceras ni el cuerpo.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = ApiExceptionHandler.CORRELATION_ID;
    static final String UNMATCHED = "(sin ruta)";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        MDC.put(MDC_KEY, UUID.randomUUID().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            log.info("HTTP {} {} -> {} in {} ms", request.getMethod(), pattern == null ? UNMATCHED : pattern,
                    response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove(MDC_KEY);
        }
    }
}
