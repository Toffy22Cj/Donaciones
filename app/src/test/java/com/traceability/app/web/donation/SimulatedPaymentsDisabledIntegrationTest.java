package com.traceability.app.web.donation;

import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.payments.SimulatedPaymentProvider;
import com.traceability.app.application.payments.SimulatedWebhookSignature;
import com.traceability.convocatoria.application.port.out.PaymentProviderPort;
import com.traceability.convocatoria.application.service.GatewayPaymentService;
import com.traceability.convocatoria.domain.exception.SimulatedPaymentsNotAllowedException;
import com.traceability.convocatoria.domain.model.PaymentProviders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enmienda 3 de ADR-037, D2 (E3-Q1, dos barreras) y requisito 2 del webhook simulado, en el perfil por defecto (sin
 * {@code traceability.demo.simulated-payments}): ni el proveedor simulado ni la ruta del webhook existen, y
 * {@code convocatoria} rechaza {@code SIMULATED} aunque llegue por otra vía.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class SimulatedPaymentsDisabledIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private GatewayPaymentService gatewayPayments;

    @Test
    void theSimulatedWebhookRouteDoesNotExist() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/webhooks/payments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"payment.confirmed\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(context.getBeansOfType(SimulatedWebhookSignature.class)).isEmpty();
    }

    @Test
    void firstBarrier_thereIsNoSimulatedProvider() {
        assertThat(context.getBeansOfType(SimulatedPaymentProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentProviderPort.class)).isEmpty();
    }

    @Test
    void secondBarrier_convocatoriaRejectsSimulatedEvenIfItArrivesAnotherWay() {
        assertThatThrownBy(() -> gatewayPayments.requireProviderAllowed(PaymentProviders.SIMULATED))
                .isInstanceOf(SimulatedPaymentsNotAllowedException.class);
        assertThatThrownBy(() -> gatewayPayments.confirmGatewayPayment(PaymentProviders.SIMULATED, "sim_x", "evt", 1, "COP"))
                .isInstanceOf(SimulatedPaymentsNotAllowedException.class);
        assertThatThrownBy(() -> gatewayPayments.failGatewayPayment(PaymentProviders.SIMULATED, "sim_x", "evt"))
                .isInstanceOf(SimulatedPaymentsNotAllowedException.class);
    }
}
