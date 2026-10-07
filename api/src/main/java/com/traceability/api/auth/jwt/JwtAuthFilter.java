package com.traceability.api.auth.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.api.auth.PublicRoutes;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Autenticación JWT *deny-by-default* sobre {@code /api/v1/**} (plan B3 §2.3; ADR-047 D5).
 *
 * <ul>
 *   <li>Toda ruta exige un JWT válido salvo las de {@link PublicRoutes}, la única lista de rutas públicas.</li>
 *   <li>Una ruta no canónica ({@code ..}, {@code //}, {@code ;}, {@code %}, {@code \}) nunca se considera pública.</li>
 *   <li>Tras verificar el token, {@code resolvePrincipal(sub)} en cada request: una cuenta desactivada o inexistente
 *       pierde el acceso en la siguiente request. El principal queda en el atributo {@link #PRINCIPAL_ATTRIBUTE}.</li>
 *   <li>Cualquier fallo es el mismo 401, con el mismo cuerpo byte a byte. Un fallo de acceso a datos no se disfraza de
 *       401: se propaga.</li>
 *   <li>Nunca registra el token, la cabecera {@code Authorization} ni el {@code kid} recibido (D7); solo el motivo
 *       interno del rechazo, en DEBUG.</li>
 * </ul>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "authorizationPrincipal";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String API_PREFIX = "/api/v1";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenVerifier verifier;
    private final IdentityPrincipalPort identityPrincipalPort;
    private final byte[] unauthorizedBody;

    public JwtAuthFilter(JwtTokenVerifier verifier, IdentityPrincipalPort identityPrincipalPort, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.identityPrincipalPort = identityPrincipalPort;
        this.unauthorizedBody = unauthorizedBody(objectMapper);
    }

    private static byte[] unauthorizedBody(ObjectMapper objectMapper) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
        problem.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        try {
            return objectMapper.writeValueAsBytes(problem);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize the 401 body", e);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        return isCanonical(path) && !(path.equals(API_PREFIX) || path.startsWith(API_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathWithinApplication(request);
        Optional<PublicRoutes.Access> access = isCanonical(path)
                ? PublicRoutes.accessFor(request.getMethod(), path)
                : Optional.empty();
        String authorization = request.getHeader("Authorization");

        if (access.isPresent() && (access.get() == PublicRoutes.Access.PUBLIC || authorization == null)) {
            chain.doFilter(request, response);
            return;
        }

        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            reject(response, "missing bearer token");
            return;
        }
        JwtVerification verification = verifier.verify(authorization.substring(BEARER_PREFIX.length()).trim());
        if (!verification.isAccepted()) {
            reject(response, verification.rejectReason().name());
            return;
        }

        AuthorizationPrincipal principal;
        try {
            principal = identityPrincipalPort.resolvePrincipal(verification.subject());
        } catch (DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            // api no depende de identity: InactiveAccountException y AccountNotFoundException llegan como genéricas
            reject(response, "principal not resolvable (" + e.getClass().getSimpleName() + ")");
            return;
        }
        if (principal == null) {
            reject(response, "principal not resolvable");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String reason) throws IOException {
        log.debug("JWT authentication rejected: {}", reason);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/problem+json");
        response.getOutputStream().write(unauthorizedBody);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }

    /** Sin codificaciones, parámetros de ruta, barras dobles o invertidas, ni segmentos {@code .} o {@code ..}. */
    static boolean isCanonical(String path) {
        if (path.isEmpty() || path.contains("%") || path.contains(";") || path.contains("\\") || path.contains("//")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
