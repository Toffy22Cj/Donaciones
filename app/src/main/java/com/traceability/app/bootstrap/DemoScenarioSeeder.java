package com.traceability.app.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.application.payments.SimulatedWebhookSignature;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.OrganizationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Semilla del escenario completo de la presentación (encargo 6, P5). <b>Solo con el perfil {@code demo-seed}</b> y
 * sobre una base vacía; si no lo está, falla con un mensaje claro y no crea nada.
 * <p>
 * Todo pasa por la API HTTP de la propia aplicación, como lo haría un front: registro, login, crear organización,
 * verificarla, convocatorias, asignaciones, cierre, donaciones con el webhook simulado firmado, asignación de fondos,
 * activos por los caminos A y B, división y logística. Solo dos cosas usan servicios de aplicación, porque por HTTP
 * exigen algo que una semilla no tiene: el arranque del administrador de plataforma
 * ({@link BootstrapPlatformAuthorityService}, sin ruta por diseño) y las altas de administrador y empleados
 * ({@link AddEmployeeService}, {@link AssignAdministratorService}), que por HTTP llegan con el token de un correo de
 * invitación. <b>Nunca</b> hay inserciones directas en la base.
 * <p>
 * La convocatoria activa se fecha para que el día de la demo ({@code traceability.demo.seed.demo-day}, por defecto el
 * 21 de octubre) caiga en el 30 % de su duración: la predicción da cifra. Deja las credenciales <b>de ejemplo, solo
 * locales</b>, y los códigos generados en {@code traceability.demo.seed.output-file}.
 */
@Component
@Profile("demo-seed")
public class DemoScenarioSeeder {

    public static final String PLATFORM = "plataforma@demo.paxfide.local";
    public static final String REPRESENTATIVE = "representante@demo.paxfide.local";
    public static final String ADMINISTRATOR = "administrador@demo.paxfide.local";
    public static final String EMPLOYEE_1 = "empleado1@demo.paxfide.local";
    public static final String EMPLOYEE_2 = "empleado2@demo.paxfide.local";
    public static final String DONOR = "donante@demo.paxfide.local";
    public static final String PENDING_REPRESENTATIVE = "representante@empresa-aliada.demo.paxfide.local";
    static final List<String> MUST_BE_EMPTY = List.of("accounts", "organizations", "convocatorias", "event_store",
            "donation_intents", "merkle_batches");
    /** La convocatoria activa está en este punto de su duración el día de la demo. */
    static final double DEMO_DAY_FRACTION = 0.30;

    private static final Logger log = LoggerFactory.getLogger(DemoScenarioSeeder.class);
    private static final AuditActor SEED = new AuditActor.SystemAuditActor("demo-seed");

    /** Lo que la semilla deja creado (para el fichero de credenciales y los tests). */
    public record SeedResult(String organizationId, String pendingOrganizationId, String activeCampaignRef,
                             String activePublicCode, String closedPublicCode, String privatePublicCode,
                             Map<String, String> trackingCodes, Path outputFile) {}

    private final MongoTemplate mongoTemplate;
    private final BootstrapPlatformAuthorityService bootstrap;
    private final AddEmployeeService employees;
    private final AssignAdministratorService administrators;
    private final ObjectProvider<SimulatedWebhookSignature> signature;
    private final Clock clock;
    private final String password;
    private final Instant demoDay;
    private final Path outputFile;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    public DemoScenarioSeeder(MongoTemplate mongoTemplate, BootstrapPlatformAuthorityService bootstrap,
                              AddEmployeeService employees, AssignAdministratorService administrators,
                              ObjectProvider<SimulatedWebhookSignature> signature, ObjectProvider<Clock> clock,
                              @Value("${traceability.demo.seed.password:}") String password,
                              @Value("${traceability.demo.seed.demo-day:2026-10-21T15:00:00Z}") Instant demoDay,
                              @Value("${traceability.demo.seed.output-file:demo-evidencia/credenciales-locales.md}")
                              Path outputFile) {
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("La semilla de la demo necesita TRACEABILITY_DEMO_SEED_PASSWORD "
                    + "(al menos 12 caracteres; contraseña de ejemplo, solo local)");
        }
        this.mongoTemplate = mongoTemplate;
        this.bootstrap = bootstrap;
        this.employees = employees;
        this.administrators = administrators;
        this.signature = signature;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.password = password;
        this.demoDay = demoDay;
        this.outputFile = outputFile;
    }

    public SeedResult seed(int port) {
        requireEmptyDatabase();
        SimulatedWebhookSignature webhook = signature.getIfAvailable();
        if (webhook == null) {
            throw new IllegalStateException("La semilla de la demo necesita los pagos simulados "
                    + "(perfil dev o traceability.demo.simulated-payments=true y TRACEABILITY_DEMO_WEBHOOK_SECRET)");
        }
        Api api = new Api("http://127.0.0.1:" + port);

        // 1. Cuentas por la API; el administrador de plataforma, por el servicio de arranque (no tiene ruta)
        for (String email : List.of(PLATFORM, REPRESENTATIVE, ADMINISTRATOR, EMPLOYEE_1, EMPLOYEE_2, DONOR,
                PENDING_REPRESENTATIVE)) {
            api.post("registro " + email, "/api/v1/auth/register", Map.of("email", email, "password", password), null,
                    false, 201);
        }
        bootstrap.bootstrap(PLATFORM);
        Map<String, String> token = new LinkedHashMap<>();
        for (String email : List.of(PLATFORM, REPRESENTATIVE, ADMINISTRATOR, EMPLOYEE_1, EMPLOYEE_2, DONOR,
                PENDING_REPRESENTATIVE)) {
            token.put(email, api.post("login " + email, "/api/v1/auth/login", Map.of("email", email, "password", password),
                    null, false, 200).get("token").asText());
        }

        // 2. Organizaciones: una verificada con su equipo y otra pendiente, en la cola de la plataforma
        String org = api.post("crear organización", "/api/v1/organizations",
                Map.of("type", "FOUNDATION", "name", "Fundación Demo PaxFide"), token.get(REPRESENTATIVE), false, 201)
                .get("organizationId").asText();
        String pendingOrg = api.post("crear organización pendiente", "/api/v1/organizations",
                Map.of("type", "COMPANY", "name", "Empresa Aliada Demo"), token.get(PENDING_REPRESENTATIVE), false, 201)
                .get("organizationId").asText();
        OrganizationId organizationId = new OrganizationId(org);
        AccountId administrator = new AccountId(api.get("me", "/api/v1/me", token.get(ADMINISTRATOR)).get("accountId").asText());
        employees.addEmployee(SEED, organizationId, administrator);
        administrators.assignAdministrator(SEED, organizationId, administrator);
        String employee1 = api.get("me", "/api/v1/me", token.get(EMPLOYEE_1)).get("accountId").asText();
        String employee2 = api.get("me", "/api/v1/me", token.get(EMPLOYEE_2)).get("accountId").asText();
        employees.addEmployee(SEED, organizationId, new AccountId(employee1));
        employees.addEmployee(SEED, organizationId, new AccountId(employee2));
        api.post("verificar organización", "/api/v1/platform/organizations/" + org + "/verify", null, token.get(PLATFORM),
                false, 200);

        // 3. Convocatorias: activa (30 % el día de la demo), cerrada y privada por enlace
        Instant start = clock.instant().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(1));
        Duration toDemoDay = Duration.between(start, demoDay);
        boolean demoDayAhead = toDemoDay.compareTo(Duration.ofDays(1)) >= 0;
        Instant activeEnd = demoDayAhead
                ? start.plusSeconds(Math.round(toDemoDay.toSeconds() / DEMO_DAY_FRACTION))
                : start.plus(Duration.ofDays(40));
        if (!demoDayAhead) {
            log.warn("Semilla de la demo: el día de la demo ({}) no está al menos un día por delante; la convocatoria "
                    + "activa dura 40 días desde hoy", demoDay);
        }
        String admin = token.get(ADMINISTRATOR);
        JsonNode active = campaign(api, admin, org, "Abrigo para el invierno", "PUBLIC", start, activeEnd,
                List.of("MONETARY", "IN_KIND"), "2000000000");
        JsonNode closed = campaign(api, admin, org, "Útiles escolares 2026", "PUBLIC", start,
                start.plus(Duration.ofDays(30)), List.of("MONETARY"), "500000000");
        JsonNode privateLink = campaign(api, admin, org, "Kits de higiene (enlace privado)", "PRIVATE_LINK", start,
                start.plus(Duration.ofDays(45)), List.of("MONETARY"), "300000000");
        String activeRef = active.get("campaignRef").asText();
        api.post("asignar empleado 1", "/api/v1/campaigns/" + activeRef + "/employees", Map.of("employeeRef", employee1),
                admin, true, 201);
        api.post("asignar empleado 2", "/api/v1/campaigns/" + closed.get("campaignRef").asText() + "/employees",
                Map.of("employeeRef", employee2), admin, true, 201);

        // 4. Donaciones por el webhook simulado firmado; una fallida
        Map<String, String> tracking = new LinkedHashMap<>();
        tracking.put("donación anónima", donate(api, webhook, active.get("publicCode").asText(), "60000000", null, true));
        tracking.put("donación con cuenta", donate(api, webhook, active.get("publicCode").asText(), "40000000",
                token.get(DONOR), true));
        donate(api, webhook, active.get("publicCode").asText(), "15000000", null, false);
        tracking.put("donación a la convocatoria cerrada", donate(api, webhook, closed.get("publicCode").asText(),
                "30000000", token.get(DONOR), true));
        tracking.put("donación privada", donate(api, webhook, privateLink.get("publicCode").asText(), "20000000", null, true));

        // 5. Cierre (D-06: la asignación del empleado 2 pasa a histórica) y el empleado 2, a la privada
        api.post("cerrar convocatoria", "/api/v1/campaigns/" + closed.get("campaignRef").asText() + "/close", null, admin,
                true, 200);
        api.post("asignar empleado 2 a la privada", "/api/v1/campaigns/" + privateLink.get("campaignRef").asText()
                + "/employees", Map.of("employeeRef", employee2), admin, true, 201);

        // 6. Camino A: fondos de la donación anónima, asignación, activo, división y entregas
        String fundId = null;
        for (JsonNode f : api.get("fondos", "/api/v1/organizations/" + org + "/funds", admin).get("items")) {
            if (activeRef.equals(f.path("campaignRef").asText()) && "60000000".equals(f.path("clearedAmount").asText())) {
                fundId = f.get("fundId").asText();
            }
        }
        if (fundId == null) {
            throw new IllegalStateException("Semilla de la demo: no aparece el fondo de la donación anónima");
        }
        String allocationId = api.post("asignación de fondos", "/api/v1/funds/" + fundId + "/allocations",
                Map.of("amount", "50000000"), admin, true, 201).get("allocationId").asText();
        String employee = token.get(EMPLOYEE_1);
        String parent = api.post("registrar activo (camino A)", "/api/v1/physical-assets/register", Map.of(
                "fundId", fundId, "assetType", "BLANKET", "quantity", "10", "unitOfMeasure", "UNITS",
                "custodianRef", "bodega-central", "currentLocation", "bodega-central", "allocationId", allocationId),
                employee, true, 201).get("assetRef").asText();
        String child = api.post("dividir", "/api/v1/physical-assets/" + parent + "/split", Map.of("quantity", "4"),
                employee, true, 202).get("childAssetRef").asText();
        until("hijo de la división", () -> "CHILD_CREATED".equals(api.get("estado de la división",
                "/api/v1/physical-assets/" + parent + "/splits/" + child, employee).path("status").asText()));
        for (String asset : List.of(parent, child)) {
            String base = "/api/v1/physical-assets/" + asset;
            api.post("despachar", base + "/dispatch", Map.of("carrierRef", "transportista-1"), employee, true, 200);
            api.post("recibir", base + "/receive", Map.of("facilityLocation", "centro-comunitario", "receiverRef",
                    "coordinador-centro"), employee, true, 200);
            api.post("entregar", base + "/deliver", Map.of("finalCustodianRef", "coordinador-centro", "beneficiaryRef",
                    "familia-1", "locationRef", "centro-comunitario", "evidenceRef", "acta-1"), employee, true, 200);
        }

        // 7. Camino B: donación en especie a la convocatoria activa, en tránsito
        String inKind = api.post("registrar activo (camino B)", "/api/v1/physical-assets/from-donation", Map.of(
                "assetType", "FOOD_KIT", "quantity", "20", "unitOfMeasure", "UNITS", "custodianRef", "bodega-central",
                "currentLocation", "bodega-central", "campaignRef", activeRef), employee, true, 201).get("assetRef").asText();
        api.post("despachar (camino B)", "/api/v1/physical-assets/" + inKind + "/dispatch",
                Map.of("carrierRef", "transportista-2"), employee, true, 200);

        SeedResult result = new SeedResult(org, pendingOrg, activeRef, active.get("publicCode").asText(),
                closed.get("publicCode").asText(), privateLink.get("publicCode").asText(), Map.copyOf(tracking), outputFile);
        write(result, start, activeEnd, demoDayAhead);
        log.info("Semilla de la demo completa; credenciales locales en {}", outputFile.toAbsolutePath());
        return result;
    }

    private void requireEmptyDatabase() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String c : MUST_BE_EMPTY) {
            long n = mongoTemplate.collectionExists(c) ? mongoTemplate.getCollection(c).countDocuments() : 0;
            if (n > 0) {
                counts.put(c, n);
            }
        }
        if (!counts.isEmpty()) {
            throw new IllegalStateException("La base de datos no está vacía " + counts + ". La semilla de la demo "
                    + "(perfil demo-seed) solo se ejecuta sobre una base vacía y sin la semilla antigua "
                    + "(TRACEABILITY_DEMO_SEED_ENABLED=false). Para empezar de cero: "
                    + "docker compose -f scripts/demo/docker-compose.yml down -v y repetir los pasos del runbook.");
        }
    }

    private JsonNode campaign(Api api, String admin, String org, String title, String visibility, Instant start,
                              Instant end, List<String> types, String targetAmount) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("acceptedDonationTypes", types);
        configuration.put("acceptedPaymentMethods", List.of("GATEWAY"));
        configuration.put("currency", "COP");
        configuration.put("targetAmount", targetAmount);
        configuration.put("targetPolicy", "FLEXIBLE");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", "Convocatoria de la demo local");
        body.put("visibility", visibility);
        body.put("startDate", start.toString());
        body.put("endDate", end.toString());
        body.put("configuration", configuration);
        return api.post("crear convocatoria " + title, "/api/v1/organizations/" + org + "/campaigns", body, admin, true, 201);
    }

    /** Intención con el proveedor simulado y su webhook firmado; devuelve el {@code trackingCode} si se confirma. */
    private String donate(Api api, SimulatedWebhookSignature webhook, String publicCode, String amount, String donorToken,
                          boolean confirmed) {
        JsonNode intent = api.post("donar", "/api/v1/public/campaigns/" + publicCode + "/donation-intents",
                Map.of("amount", amount, "currency", "COP", "paymentMethod", "GATEWAY"), donorToken, true, 201);
        String url = intent.get("paymentRedirectUrl").asText();
        String event;
        try {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("type", confirmed ? "payment.confirmed" : "payment.failed");
            e.put("paymentSessionId", url.substring(url.lastIndexOf('/') + 1));
            e.put("providerEventId", "evt-" + UUID.randomUUID());
            e.put("amount", amount);
            e.put("currency", "COP");
            event = json.writeValueAsString(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        api.send("webhook de pago", "POST", "/api/v1/webhooks/payments", event, null, false,
                Map.of("X-Simulated-Signature", webhook.sign(event)), 200);
        if (!confirmed) {
            return null;
        }
        String intentPath = "/api/v1/public/donation-intents/" + intent.get("intentId").asText();
        String statusToken = intent.get("statusToken").asText();
        String[] code = new String[1];
        until("trackingCode", () -> {
            JsonNode status = api.send("estado de la intención", "GET", intentPath, null, null, false,
                    Map.of("Intent-Token", statusToken), 200);
            code[0] = status.path("trackingCode").asText(null);
            return code[0] != null;
        });
        return code[0];
    }

    private void write(SeedResult r, Instant start, Instant activeEnd, boolean demoDayAhead) {
        StringBuilder md = new StringBuilder();
        md.append("# Credenciales y códigos de la demo — **de ejemplo, solo locales**\n\n");
        md.append("Generado por la semilla de la demo (perfil `demo-seed`, `runbook-demo-local.md`) el ")
                .append(clock.instant().truncatedTo(ChronoUnit.SECONDS)).append(". Son cuentas y códigos de una base ")
                .append("local de demostración: **nunca** se usan en otro entorno ni son credenciales reales. Cada ")
                .append("ejecución sobre una base vacía genera códigos nuevos.\n\n");
        md.append("## Cuentas (contraseña común: `").append(password)
                .append("`, la de `TRACEABILITY_DEMO_SEED_PASSWORD`)\n\n| Papel | Email |\n|---|---|\n");
        md.append("| Administrador de plataforma | ").append(PLATFORM).append(" |\n");
        md.append("| Representante (Fundación Demo PaxFide, verificada) | ").append(REPRESENTATIVE).append(" |\n");
        md.append("| Administrador (y empleado) de la fundación | ").append(ADMINISTRATOR).append(" |\n");
        md.append("| Empleado 1 (responsable de la convocatoria activa) | ").append(EMPLOYEE_1).append(" |\n");
        md.append("| Empleado 2 (fue de la cerrada; ahora de la privada) | ").append(EMPLOYEE_2).append(" |\n");
        md.append("| Donante con cuenta | ").append(DONOR).append(" |\n");
        md.append("| Representante de Empresa Aliada Demo (pendiente de verificación) | ")
                .append(PENDING_REPRESENTATIVE).append(" |\n\n");
        md.append("## Organizaciones\n\n| Organización | organizationId |\n|---|---|\n");
        md.append("| Fundación Demo PaxFide (verificada) | `").append(r.organizationId()).append("` |\n");
        md.append("| Empresa Aliada Demo (en la cola) | `").append(r.pendingOrganizationId()).append("` |\n\n");
        md.append("## Convocatorias\n\n| Clave | Convocatoria | publicCode |\n|---|---|---|\n");
        md.append("| activa | Abrigo para el invierno (PUBLIC, del ").append(start).append(" al ").append(activeEnd)
                .append(") | `").append(r.activePublicCode()).append("` |\n");
        md.append("| cerrada | Útiles escolares 2026 (CLOSED) | `").append(r.closedPublicCode()).append("` |\n");
        md.append("| privada | Kits de higiene (PRIVATE_LINK) | `").append(r.privatePublicCode()).append("` |\n\n");
        md.append("`campaignRef` de la activa (para la predicción): `").append(r.activeCampaignRef()).append("`. ");
        md.append(demoDayAhead
                ? "El " + demoDay + " estará en el 30 % de su duración: la predicción da cifra.\n\n"
                : "**Aviso:** la semilla corrió a menos de un día de " + demoDay + "; la activa dura 40 días desde hoy y "
                        + "la predicción dará cifra a partir del día 6.\n\n");
        md.append("## trackingCode (seguimiento del donante)\n\n| Donación | Convocatoria | trackingCode |\n|---|---|---|\n");
        r.trackingCodes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                md.append("| ").append(e.getKey()).append(" | ").append(campaignOf(e.getKey())).append(" | `")
                        .append(e.getValue()).append("` |\n"));
        md.append("\nContenido: donación anónima de 600 000 COP y con cuenta de 400 000 COP a la activa, y un pago ")
                .append("fallido; camino A (10 mantas, división en 6 + 4, las dos entregadas) y camino B (20 kits de ")
                .append("comida en tránsito); 300 000 COP a la cerrada y 200 000 COP a la privada.\n");
        try {
            if (r.outputFile().getParent() != null) {
                Files.createDirectories(r.outputFile().getParent());
            }
            Files.writeString(r.outputFile(), md.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String campaignOf(String donation) {
        if (donation.contains("cerrada")) return "Útiles escolares 2026";
        if (donation.contains("privada")) return "Kits de higiene (enlace privado)";
        return "Abrigo para el invierno";
    }

    private static void until(String what, Supplier<Boolean> done) {
        Instant end = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(end)) {
            if (Boolean.TRUE.equals(done.get())) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Semilla de la demo interrumpida esperando: " + what, e);
            }
        }
        throw new IllegalStateException("Semilla de la demo: tiempo agotado esperando " + what);
    }

    /** Cliente de la propia API; cualquier respuesta inesperada detiene la semilla con el paso y el estado. */
    private final class Api {
        private final String base;

        Api(String base) {
            this.base = base;
        }

        JsonNode get(String step, String path, String bearer) {
            return send(step, "GET", path, null, bearer, false, Map.of(), 200);
        }

        JsonNode post(String step, String path, Object body, String bearer, boolean commandId, int expected) {
            try {
                return send(step, "POST", path, body == null ? null : json.writeValueAsString(body), bearer, commandId,
                        Map.of(), expected);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        JsonNode send(String step, String method, String path, String body, String bearer, boolean commandId,
                      Map<String, String> headers, int expected) {
            HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) r.header("Content-Type", "application/json");
            if (bearer != null) r.header("Authorization", "Bearer " + bearer);
            if (commandId) r.header("Command-Id", UUID.randomUUID().toString());
            headers.forEach(r::header);
            try {
                HttpResponse<String> response = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != expected) {
                    throw new IllegalStateException("Semilla de la demo: el paso '" + step + "' (" + method + " " + path
                            + ") devolvió " + response.statusCode() + " en lugar de " + expected + ": " + response.body());
                }
                return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Semilla de la demo interrumpida en el paso " + step, e);
            }
        }
    }
}
