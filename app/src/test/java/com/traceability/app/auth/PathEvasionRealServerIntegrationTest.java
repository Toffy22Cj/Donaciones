package com.traceability.app.auth;

import com.traceability.api.auth.jwt.JwtAuthFilter;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.core.application.security.TrackingCodeService;
import identity.application.service.CreateAccountService;
import identity.domain.model.Email;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evasión del filtro por la forma de la ruta, contra <strong>Tomcat real</strong> (no MockMvc, que normaliza la ruta
 * antes del filtro). Las requests se envían por un socket en bruto para que ningún cliente HTTP reescriba la ruta.
 *
 * <p>Ninguna variante ({@code //}, {@code ..} a través de una ruta pública, {@code ;}, {@code %2F}, {@code %2e}) puede
 * llegar a una ruta protegida sin JWT: el resultado es 400 (Tomcat la rechaza) o 401 (el filtro la trata como
 * protegida), nunca 200. El control positivo demuestra que la ruta protegida responde 200 con un JWT válido.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TraceabilityApplication.class, PathEvasionRealServerIntegrationTest.ProtectedController.class})
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class PathEvasionRealServerIntegrationTest {

    static final String PROTECTED = "/api/v1/b3-test/protected";

    /**
     * Responde 200 aunque falte el principal: si el filtro dejara pasar una request, el test vería un 200 y no un 400
     * de Spring por el atributo ausente, que ocultaría el hueco.
     */
    @RestController
    static class ProtectedController {
        @GetMapping(PROTECTED)
        String protectedRoute(@RequestAttribute(name = JwtAuthFilter.PRINCIPAL_ATTRIBUTE, required = false) AuthorizationPrincipal principal) {
            return "secret-for-" + (principal == null ? "nobody" : principal.accountId());
        }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private CreateAccountService createAccountService;
    @Autowired private TrackingCodeService trackingCodeService;

    private String jwt;
    private String trackingCode;

    @BeforeEach
    void setUp() throws Exception {
        String email = "e" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
        createAccountService.createAccount(new Email(email), "pw-correct-123");
        Response login = send("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"pw-correct-123\"}");
        assertThat(login.status()).isEqualTo(200);
        jwt = login.body().substring("{\"token\":\"".length(), login.body().length() - 2);
        trackingCode = trackingCodeService.generate("fund-" + UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.DAYS));
    }

    @Test
    void control_theProtectedRouteAnswers200WithAValidJwt_and401Without() throws Exception {
        assertThat(send("GET", PROTECTED, jwt, null).status()).isEqualTo(200);
        assertThat(send("GET", PROTECTED, null, null).status()).isEqualTo(401);
    }

    @Test
    void doubleSlash_withoutJwt_is401() throws Exception {
        for (String path : List.of(
                "/" + PROTECTED,                          // //api/v1/b3-test/protected
                "/api/v1//b3-test/protected",
                "/api/v1/b3-test//protected")) {
            Response r = send("GET", path, null, null);
            System.out.printf("PATH-EVASION %d sin-cabecera %s%n", r.status(), path);
            assertThat(r.status()).as(path).isEqualTo(401);
            assertThat(r.body()).as(path).doesNotContain("secret-for-");
        }
    }

    @Test
    void dotDot_throughAPublicRoute_neverReachesTheProtectedOne() throws Exception {
        for (String path : List.of(
                "/api/v1/donations/tracking/../../b3-test/protected",
                "/api/v1/donations/tracking/./../../b3-test/protected",
                "/api/v1/auth/login/../../b3-test/protected",
                "/api/v1/public/campaigns/x/../../../b3-test/protected")) {
            assertNeverReached(path, null);
            // con un código de seguimiento válido: el filtro de seguimiento no puede abrir la puerta al JWT
            assertNeverReached(path, trackingCode);
        }
    }

    @Test
    void pathParametersAndEncodings_neverReachTheProtectedOne() throws Exception {
        for (String path : List.of(
                PROTECTED + ";jsessionid=x",
                "/api/v1/b3-test;x=y/protected",
                "/api/v1/donations/tracking/..;/..;/b3-test/protected",
                "/api/v1/donations/tracking;/../../b3-test/protected",
                "/api/v1/donations/tracking%2F..%2F..%2Fb3-test%2Fprotected",
                "/api/v1/donations/tracking/%2e%2e/%2e%2e/b3-test/protected",
                "/api/v1/donations/tracking/%2E%2E%2F%2E%2E%2Fb3-test/protected",
                "/api/v1/b3-test%2Fprotected",
                "/api/v1/donations/tracking/..%5c..%5cb3-test%5cprotected")) {
            assertNeverReached(path, null);
            assertNeverReached(path, trackingCode);
        }
    }

    private void assertNeverReached(String path, String bearer) throws Exception {
        Response r = send("GET", path, bearer, null);
        System.out.printf("PATH-EVASION %d %s %s%n", r.status(), bearer == null ? "sin-cabecera" : "con-codigo-de-seguimiento", path);
        assertThat(r.status()).as("%s (bearer %s)", path, bearer == null ? "none" : "tracking").isIn(400, 401);
        assertThat(r.body()).as(path).doesNotContain("secret-for-");
    }

    record Response(int status, String body) {}

    /** HTTP/1.1 por socket en bruto: la ruta llega a Tomcat exactamente como se escribe aquí. */
    private Response send(String method, String rawPath, String bearer, String jsonBody) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            byte[] body = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder()
                    .append(method).append(' ').append(rawPath).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n")
                    .append("Connection: close\r\n");
            if (bearer != null) req.append("Authorization: Bearer ").append(bearer).append("\r\n");
            if (jsonBody != null) req.append("Content-Type: application/json\r\n");
            req.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = in.readLine();
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            boolean chunked = false;
            for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
                chunked |= line.toLowerCase().startsWith("transfer-encoding:") && line.toLowerCase().contains("chunked");
            }
            StringBuilder rest = new StringBuilder();
            if (chunked) {
                for (String size; (size = in.readLine()) != null; ) {
                    int n = Integer.parseInt(size.trim().split(";")[0], 16);
                    if (n == 0) break;
                    char[] chunk = new char[n];
                    int read = 0;
                    while (read < n) read += in.read(chunk, read, n - read);
                    rest.append(chunk);
                    in.readLine();
                }
            } else {
                for (String line; (line = in.readLine()) != null; ) rest.append(line);
            }
            return new Response(status, rest.toString());
        }
    }
}
