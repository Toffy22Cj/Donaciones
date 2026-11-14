package com.traceability.api.web.ratelimit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ADR-053: límite de intentos por (grupo de rutas, IP del cliente) y, en {@code POST /api/v1/auth/login}, también por
 * correo normalizado. Va después de CORS (un preflight nunca cuenta) y antes de {@link
 * com.traceability.api.auth.jwt.JwtAuthFilter} (registro y orden en {@link RateLimitConfig}).
 * <ul>
 *   <li>Se cuentan <b>todas</b> las peticiones del grupo, no solo las fallidas: el {@code 429} no depende de que la
 *   cuenta exista ni de que la contraseña sea correcta, y no revela nada que el {@code 401} uniforme no revele.</li>
 *   <li>Rechazo: {@code 429} con {@code Retry-After} (segundos) y un {@code ProblemDetail} fijo, el mismo byte a byte
 *   para todos los grupos.</li>
 *   <li>Nunca registra el correo, la IP ni el cuerpo; solo el grupo, en DEBUG.</li>
 * </ul>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/api/v1/auth/login";
    /** El cuerpo del login se lee hasta aquí para buscar el correo; uno mayor se limita solo por IP. */
    static final int MAX_LOGIN_BODY_BYTES = 16 * 1024;

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final SlidingWindowRateLimiter limiter;
    private final ClientIpResolver clientIp;
    private final ObjectMapper objectMapper;
    private final byte[] tooManyRequestsBody;

    public RateLimitFilter(RateLimitProperties properties, SlidingWindowRateLimiter limiter, ClientIpResolver clientIp,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.limiter = limiter;
        this.clientIp = clientIp;
        this.objectMapper = objectMapper;
        this.tooManyRequestsBody = tooManyRequestsBody(objectMapper);
    }

    static byte[] tooManyRequestsBody(ObjectMapper objectMapper) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests; retry later");
        problem.setTitle(HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        try {
            return objectMapper.writeValueAsBytes(problem);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize the 429 body", e);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String path = normalizedPath(request);
        Optional<RateLimitGroup> group = RateLimitGroup.of(request.getMethod(), path);
        if (group.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        RateLimitProperties.Quota quota = properties.quotaOf(group.get());
        SlidingWindowRateLimiter.Decision byIp = limiter.tryAcquire(
                group.get().configKey() + "|" + clientIp.resolve(request), quota.getLimit(), quota.getWindow());
        if (!byIp.allowed()) {
            reject(response, group.get(), byIp);
            return;
        }

        HttpServletRequest next = request;
        if (group.get() == RateLimitGroup.LOGIN && LOGIN_PATH.equals(path)) {
            CachedPrefixRequest cached = new CachedPrefixRequest(request);
            next = cached;
            String email = normalizedEmail(cached.prefix(), cached.complete());
            if (email != null) {
                RateLimitProperties.Quota byEmailQuota = properties.getLoginEmail();
                SlidingWindowRateLimiter.Decision byEmail = limiter.tryAcquire("login-email|" + email,
                        byEmailQuota.getLimit(), byEmailQuota.getWindow());
                if (!byEmail.allowed()) {
                    reject(response, group.get(), byEmail);
                    return;
                }
            }
        }
        chain.doFilter(next, response);
    }

    private void reject(HttpServletResponse response, RateLimitGroup group, SlidingWindowRateLimiter.Decision decision)
            throws IOException {
        log.debug("Rate limit exceeded for group {}", group.configKey());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.getOutputStream().write(tooManyRequestsBody);
    }

    /**
     * La ruta que ve el {@code DispatcherServlet}: {@code servletPath + pathInfo}, que el contenedor ya decodifica y
     * limpia de {@code ;parámetros} y segmentos {@code .}/{@code ..}; además, barras repetidas reducidas a una.
     */
    static String normalizedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        String path = servletPath + pathInfo;
        if (path.isEmpty()) {
            String uri = request.getRequestURI();
            String context = request.getContextPath();
            path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        }
        return path.replaceAll("/{2,}", "/");
    }

    /** El campo {@code email} del JSON, recortado y en minúsculas; {@code null} si no se puede leer. */
    private String normalizedEmail(byte[] body, boolean complete) {
        if (!complete || body.length == 0) {
            return null;
        }
        try {
            JsonNode email = objectMapper.readTree(body).path("email");
            if (!email.isTextual()) {
                return null;
            }
            String normalized = email.asText().strip().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() ? null : normalized;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Lee los primeros bytes del cuerpo y los devuelve, seguidos del resto, a quien lea después (el controlador). */
    static final class CachedPrefixRequest extends HttpServletRequestWrapper {
        private final byte[] prefix;
        private final boolean complete;
        private final InputStream rest;

        CachedPrefixRequest(HttpServletRequest request) throws IOException {
            super(request);
            InputStream in = request.getInputStream();
            byte[] read = in.readNBytes(MAX_LOGIN_BODY_BYTES + 1);
            this.complete = read.length <= MAX_LOGIN_BODY_BYTES;
            this.prefix = read;
            this.rest = in;
        }

        byte[] prefix() {
            return prefix;
        }

        boolean complete() {
            return complete;
        }

        @Override
        public ServletInputStream getInputStream() {
            InputStream all = new SequenceInputStream(new ByteArrayInputStream(prefix), rest);
            return new ServletInputStream() {
                private boolean finished;

                @Override
                public int read() throws IOException {
                    int b = all.read();
                    finished = b < 0;
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = all.read(b, off, len);
                    finished = n < 0;
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return finished;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("async read is not supported on the login body");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    getCharacterEncoding() == null ? java.nio.charset.StandardCharsets.UTF_8
                            : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}
