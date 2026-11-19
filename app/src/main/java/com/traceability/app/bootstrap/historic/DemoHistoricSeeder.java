package com.traceability.app.bootstrap.historic;

import com.traceability.app.application.funds.FundsApplicationOutcome;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.MediaUploadService;
import com.traceability.app.application.payments.ConfirmGatewayPaymentUseCase;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaResult;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentWithAccessResult;
import com.traceability.convocatoria.application.query.OrganizationCampaignsQuery;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.application.service.DonationIntentService;
import com.traceability.convocatoria.application.service.GatewayPaymentService;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.PaymentProviders;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.core.application.command.PhysicalAssetCommandService;
import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.application.saga.SplitResolutionStatus;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.shared.EventTime;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.service.DonorPseudonymService;
import identity.domain.model.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Semilla histórica de la demo (perfil {@code demo-historico}; plan del paso 2, 2026-11-16). Añade a la organización
 * de {@code demo-seed} convocatorias <b>sintéticas</b> con fechas pasadas, para ver cómo responde la predicción en cada
 * caso y dar datos a los gráficos (varios meses de fondos, lotes en varios estados).
 * <p>
 * Todo pasa por los <b>mismos servicios de aplicación</b> que usa la API: crear convocatoria, crear intención, confirmar
 * o fallar el pago, aplicar los fondos, cerrar, registrar y mover lotes. <b>Nunca</b> hay inserciones directas. Lo único
 * distinto es la fecha: cada paso se ejecuta dentro de {@link EventTime#callAt}, de modo que {@code occurredAt},
 * {@code confirmedAt} y {@code createdAt} son del pasado, mientras {@code recordedAt}, la cadena de hashes y el anclaje
 * se calculan como siempre, con la hora real.
 * <p>
 * Los pasos que resuelve una saga en otro hilo (el hijo de una división) llevan fecha real.
 */
@Component
@Profile(DemoHistoricConfig.PROFILE)
public class DemoHistoricSeeder {

    /** Todas las convocatorias de esta semilla empiezan así: son datos de demostración, nunca reales. */
    public static final String TITLE_PREFIX = "[Demo] ";
    public static final String DESCRIPTION = "Datos sintéticos de demostración (perfil demo-historico). No son reales.";

    static final String ADMINISTRATOR = "administrador@demo.paxfide.local";
    static final String DONOR = "donante@demo.paxfide.local";
    /** DIVIPOLA de BOGOTÁ, D.C.: está en el catálogo real y en el de prueba. */
    static final String MUNICIPALITY = "11001";

    private static final Logger log = LoggerFactory.getLogger(DemoHistoricSeeder.class);

    /** Qué responde la predicción para una convocatoria recién sembrada. */
    public enum Expected {
        FIGURE, OUTSIDE_TRAINED_RANGE, TARGET_ALREADY_REACHED, STRICT_POLICY_EXCLUDED, UNSUPPORTED_CURRENCY, CAMPAIGN_ENDED
    }

    /**
     * Una convocatoria del escenario. {@code elapsed} es la fracción de su duración ya transcurrida al sembrar;
     * {@code raised}, la fracción de la meta confirmada; {@code closedAt}, la fracción en la que se cierra antes de
     * tiempo (o {@code null}).
     */
    record Spec(String key, String title, int durationDays, double elapsed, String currency, TargetPolicy policy,
                long target, double raised, int donations, int failed, int pending, boolean inKind, Double closedAt,
                Expected expected) {}

    static final long COP_20M = 2_000_000_000L;

    /**
     * El escenario aprobado: antes, dentro y después del rango; meta alcanzada; STRICT; otra moneda; cerrada antes.
     * Dentro del rango, lo recaudado se eligió para que la estimación recorra de muy baja a muy alta: «Inicio del
     * rango» y «Cuarenta por ciento» van al ritmo lineal o por debajo (el modelo, entrenado con convocatorias que
     * recaudan pronto, les da poca probabilidad); «Un cuarto» y «Cerca del límite» van adelantadas.
     */
    static final List<Spec> SCENARIO = List.of(
            new Spec("before", "Antes del rango", 120, 0.07, "COP", TargetPolicy.FLEXIBLE, COP_20M, 0.03, 3, 1, 0,
                    false, null, Expected.OUTSIDE_TRAINED_RANGE),
            new Spec("start", "Inicio del rango", 200, 0.17, "COP", TargetPolicy.FLEXIBLE, COP_20M, 0.12, 6, 1, 1,
                    false, null, Expected.FIGURE),
            new Spec("quarter", "Un cuarto", 200, 0.25, "COP", TargetPolicy.FLEXIBLE, 3_000_000_000L, 0.45, 30, 2, 0,
                    false, null, Expected.FIGURE),
            new Spec("forty", "Cuarenta por ciento", 200, 0.40, "COP", TargetPolicy.FLEXIBLE, 2_500_000_000L, 0.45, 10,
                    2, 1, true, null, Expected.FIGURE),
            new Spec("limit", "Cerca del límite", 300, 0.47, "COP", TargetPolicy.FLEXIBLE, 4_000_000_000L, 0.80, 50, 2,
                    0, false, null, Expected.FIGURE),
            new Spec("after", "Después del rango", 200, 0.65, "COP", TargetPolicy.FLEXIBLE, 3_000_000_000L, 0.80, 12, 2,
                    1, true, null, Expected.OUTSIDE_TRAINED_RANGE),
            new Spec("reached", "Meta alcanzada", 120, 0.30, "COP", TargetPolicy.FLEXIBLE, 1_000_000_000L, 1.05, 6, 1,
                    0, false, null, Expected.TARGET_ALREADY_REACHED),
            new Spec("strict", "Política estricta", 120, 0.30, "COP", TargetPolicy.STRICT, 1_500_000_000L, 0.20, 4, 1,
                    0, false, null, Expected.STRICT_POLICY_EXCLUDED),
            new Spec("usd", "En dólares", 120, 0.30, "USD", TargetPolicy.FLEXIBLE, 1_000_000L, 0.25, 4, 1, 0, false,
                    null, Expected.UNSUPPORTED_CURRENCY),
            new Spec("closedEarly", "Cerrada antes de tiempo", 100, 0.20, "COP", TargetPolicy.FLEXIBLE, COP_20M, 0.04,
                    2, 0, 0, false, 0.05, Expected.CAMPAIGN_ENDED));

    /** Lo que quedó creado, para los tests y para la tabla de evidencia. */
    public record SeededCampaign(String key, String title, String campaignRef, String publicCode, Instant startDate,
                                 Instant endDate, Expected expected, int confirmed, int failed, int pending,
                                 int fundingRejected) {}

    public record SeededAsset(String assetRef, String campaignKey, String state, boolean occurredAtIsReal) {}

    public record Result(String organizationId, Instant seededAt, List<SeededCampaign> campaigns,
                         List<SeededAsset> assets) {}

    private final AccountRepositoryPort accounts;
    private final IdentityPrincipalPort principals;
    private final DonorPseudonymService pseudonyms;
    private final OrganizationCampaignsQuery organizationCampaigns;
    private final ConvocatoriaLifecycleService lifecycle;
    private final DonationIntentService intents;
    private final ConfirmGatewayPaymentUseCase payments;
    private final PhysicalAssetCommandService assets;
    private final PhysicalAssetOperationalReadPort assetReads;
    private final MediaUploadService uploads;

    public DemoHistoricSeeder(AccountRepositoryPort accounts, IdentityPrincipalPort principals,
                              DonorPseudonymService pseudonyms, OrganizationCampaignsQuery organizationCampaigns,
                              ConvocatoriaLifecycleService lifecycle, DonationIntentService intents, ConfirmGatewayPaymentUseCase payments,
                              PhysicalAssetCommandService assets, PhysicalAssetOperationalReadPort assetReads,
                              MediaUploadService uploads) {
        this.accounts = accounts;
        this.principals = principals;
        this.pseudonyms = pseudonyms;
        this.organizationCampaigns = organizationCampaigns;
        this.lifecycle = lifecycle;
        this.intents = intents;
        this.payments = payments;
        this.assets = assets;
        this.assetReads = assetReads;
        this.uploads = uploads;
    }

    public Result seed() {
        String administrator = accountOf(ADMINISTRATOR);
        String donor = accountOf(DONOR);
        AuthorizationPrincipal principal = principals.resolvePrincipal(administrator);
        String organization = principal.organizationId();
        if (organization == null) {
            throw new DemoHistoricPreconditionException("La semilla histórica necesita el escenario de demo-seed: "
                    + ADMINISTRATOR + " no pertenece a ninguna organización");
        }
        boolean alreadySeeded = organizationCampaigns.list(administrator, organization).stream()
                .anyMatch(c -> c.title().startsWith(TITLE_PREFIX));
        if (alreadySeeded) {
            throw new DemoHistoricPreconditionException("La semilla histórica ya corrió sobre esta base: la"
                    + " organización de la demo ya tiene convocatorias «" + TITLE_PREFIX.trim() + "». Para resembrar,"
                    + " vacía la base (runbook-demo-local.md)");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(1));
        String donorRef = "acct:" + pseudonyms.pseudonymFor(donor);
        List<SeededCampaign> campaigns = new ArrayList<>();
        List<SeededAsset> seededAssets = new ArrayList<>();
        for (Spec spec : SCENARIO) {
            SeededCampaign campaign = campaign(spec, now, administrator, organization, donorRef);
            campaigns.add(campaign);
            if (spec.inKind()) {
                // Sin asignar empleados: un empleado solo puede ser responsable de una convocatoria activa, y los de
                // demo-seed ya lo son. Los lotes los mueve el administrador, que también es EMPLOYEE.
                seededAssets.addAll(assets(spec, campaign, now, organization, administrator));
            }
        }
        log.info("Semilla histórica completa: {} convocatorias y {} lotes sintéticos en la organización {}",
                campaigns.size(), seededAssets.size(), organization);
        return new Result(organization, now, List.copyOf(campaigns), List.copyOf(seededAssets));
    }

    private String accountOf(String email) {
        return accounts.findByEmail(new Email(email)).map(a -> a.getAccountId().value())
                .orElseThrow(() -> new DemoHistoricPreconditionException("La semilla histórica necesita el escenario"
                        + " de demo-seed: no existe la cuenta " + email + ". Arranca con los perfiles"
                        + " dev,demo-seed,demo-historico sobre una base vacía"));
    }

    private SeededCampaign campaign(Spec spec, Instant now, String administrator, String organization, String donorRef) {
        Duration duration = Duration.ofDays(spec.durationDays());
        Duration elapsed = Duration.ofSeconds(Math.round(duration.toSeconds() * spec.elapsed()));
        Instant start = now.minus(elapsed);
        Instant end = start.plus(duration);
        Set<DonationType> types = spec.inKind() ? EnumSet.of(DonationType.MONETARY, DonationType.IN_KIND)
                : EnumSet.of(DonationType.MONETARY);
        ConvocatoriaConfiguration configuration = new ConvocatoriaConfiguration(types, EnumSet.of(PaymentMethod.GATEWAY),
                spec.currency(), spec.target(), spec.policy(), null);
        CreateConvocatoriaResult created = at(start, () -> lifecycle.createConvocatoria(new CreateConvocatoriaCommand(
                commandId(), administrator, organization, TITLE_PREFIX + spec.title(), DESCRIPTION, Visibility.PUBLIC,
                start, end, configuration)));

        // Hasta dónde llegan las donaciones: hasta el cierre anticipado o hasta ahora
        Duration window = spec.closedAt() == null ? elapsed
                : Duration.ofSeconds(Math.round(duration.toSeconds() * spec.closedAt()));
        int confirmed = 0;
        long[] amounts = amounts(spec);
        for (int i = 0; i < spec.donations(); i++) {
            Instant when = start.plus(fraction(window, (i + 0.5) / spec.donations() * 0.97));
            String ref = i % 3 == 2 ? donorRef : "anon:" + UUID.randomUUID();
            FundsApplicationOutcome outcome = donate(created.publicCode(), ref, amounts[i], spec.currency(), when, true);
            require(outcome == FundsApplicationOutcome.APPLIED, spec, "una donación confirmada dio " + outcome);
            confirmed++;
        }
        for (int i = 0; i < spec.failed(); i++) {
            Instant when = start.plus(fraction(window, (i + 0.35) / spec.failed() * 0.9));
            donate(created.publicCode(), "anon:" + UUID.randomUUID(), amounts[0], spec.currency(), when, false);
        }
        int rejected = 0;
        if (spec.policy() == TargetPolicy.STRICT) {
            // Excede la meta: STRICT la rechaza después de confirmada (FUNDING_REJECTED), y el dinero no entra
            FundsApplicationOutcome outcome = donate(created.publicCode(), "anon:" + UUID.randomUUID(),
                    Math.round(spec.target() * 0.9), spec.currency(), start.plus(fraction(window, 0.98)), true);
            require(outcome == FundsApplicationOutcome.FUNDING_REJECTED, spec,
                    "la donación que excede la meta dio " + outcome);
            rejected++;
        }
        for (int i = 0; i < spec.pending(); i++) {
            // Intención abierta y sin resolver: el donante no terminó el pago
            Instant when = start.plus(fraction(window, 0.99));
            at(when, () -> intents.createDonationIntentWithAccess(new CreateDonationIntentCommand(commandId(),
                    created.publicCode(), "anon:" + UUID.randomUUID(), amounts[0], spec.currency(),
                    PaymentMethod.GATEWAY)));
        }
        if (spec.closedAt() != null) {
            at(start.plus(window), () -> lifecycle.closeConvocatoria(
                    new CloseConvocatoriaCommand(commandId(), administrator, created.campaignRef())));
        }
        return new SeededCampaign(spec.key(), TITLE_PREFIX + spec.title(), created.campaignRef(), created.publicCode(),
                start, end, spec.expected(), confirmed, spec.failed(), spec.pending(), rejected);
    }

    /** Importes deterministas y desiguales que suman {@code raised × target}. */
    static long[] amounts(Spec spec) {
        long total = Math.round(spec.target() * spec.raised());
        double[] weights = new double[spec.donations()];
        double sum = 0;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = 0.6 + ((i * 37 + spec.key().length() * 11) % 9) / 10.0;
            sum += weights[i];
        }
        long[] amounts = new long[weights.length];
        long assigned = 0;
        for (int i = 0; i < weights.length - 1; i++) {
            amounts[i] = Math.max(1, Math.round(total * weights[i] / sum));
            assigned += amounts[i];
        }
        amounts[weights.length - 1] = Math.max(1, total - assigned);
        return amounts;
    }

    /** Crea la intención y resuelve el pago, todo fechado en {@code when}. Devuelve el resultado de aplicar los fondos. */
    private FundsApplicationOutcome donate(String publicCode, String donorRef, long amount, String currency,
                                           Instant when, boolean confirmed) {
        return at(when, () -> {
            CreateDonationIntentWithAccessResult intent = intents.createDonationIntentWithAccess(
                    new CreateDonationIntentCommand(commandId(), publicCode, donorRef, amount, currency,
                            PaymentMethod.GATEWAY));
            String url = intent.paymentRedirectUrl();
            String session = url.substring(url.lastIndexOf('/') + 1);
            if (!confirmed) {
                GatewayPaymentService.FailureOutcome failure = payments.fail(PaymentProviders.SIMULATED, session,
                        "evt-" + UUID.randomUUID());
                if (failure != GatewayPaymentService.FailureOutcome.FAILED) {
                    throw new DemoHistoricSeedFailedException("Un pago fallido dio " + failure);
                }
                return null;
            }
            return payments.confirm(PaymentProviders.SIMULATED, session, "evt-" + UUID.randomUUID(), amount, currency)
                    .application();
        });
    }

    /**
     * Lotes en especie (camino B) de una convocatoria: registrado, despachado, recibido y entregado. En «Después del
     * rango», además, uno dividido: la división se hace ahora y su hijo nace por la saga, con fecha real.
     */
    private List<SeededAsset> assets(Spec spec, SeededCampaign campaign, Instant now, String organization,
                                     String administrator) {
        HumanActor actor = new HumanActor(administrator);
        Duration elapsed = Duration.between(campaign.startDate(), now);
        List<SeededAsset> out = new ArrayList<>();
        String[] states = {"REGISTERED", "DISPATCHED", "RECEIVED", "DELIVERED"};
        String[] types = {"BLANKET", "FOOD_KIT", "HYGIENE_KIT", "SCHOOL_KIT"};
        for (int i = 0; i < states.length; i++) {
            int lot = i;
            // ADR-051 Enmienda 2, Tarea 2 (§2.5): los lotes recibidos y entregados llevan una foto de ejemplo (imagen sintética,
            // nunca de personas) en cada paso que ya pasaron, para las capturas de la web; los demás, ninguna
            boolean withPhotos = lot >= 2;
            int photoBase = 100 + SCENARIO.indexOf(spec) * 30 + lot * 3;
            Instant registeredAt = campaign.startDate().plus(fraction(elapsed, 0.20 + 0.12 * lot));
            String asset = at(registeredAt, () -> assets.registerPhysicalAssetFromDonation(commandId(), organization,
                    "anon:" + UUID.randomUUID(), types[lot], BigDecimal.valueOf(10L + 5L * lot), "UNITS",
                    "bodega-central", "bodega-central", MUNICIPALITY, campaign.campaignRef(),
                    withPhotos ? stepPhoto(MediaPurpose.REGISTRATION_EVIDENCE, organization, administrator, photoBase) : null,
                    actor)).assetId();
            if (lot >= 1) {
                at(registeredAt.plus(fraction(elapsed, 0.03)), () -> {
                    assets.dispatchAsset(commandId(), asset, "transportista-demo",
                            withPhotos ? stepPhoto(MediaPurpose.DISPATCH_EVIDENCE, organization, administrator, photoBase + 1) : null,
                            actor);
                    return null;
                });
            }
            if (lot >= 2) {
                at(registeredAt.plus(fraction(elapsed, 0.06)), () -> {
                    assets.receiveAsset(commandId(), asset, "centro-comunitario", MUNICIPALITY, "coordinador-centro",
                            stepPhoto(MediaPurpose.RECEIPT_EVIDENCE, organization, administrator, photoBase + 2), actor);
                    return null;
                });
            }
            if (lot >= 3) {
                Instant deliveredAt = registeredAt.plus(fraction(elapsed, 0.09));
                at(deliveredAt, () -> {
                    String evidenceRef = "sha256:" + uploads.store(MediaPurpose.DELIVERY_EVIDENCE, organization, null,
                            administrator, photo(SCENARIO.indexOf(spec) * 10 + lot)).sha256();
                    assets.deliverAsset(commandId(), asset, "coordinador-centro", "beneficiario-demo",
                            "centro-comunitario", MUNICIPALITY, evidenceRef, deliveredAt, actor);
                    return null;
                });
            }
            out.add(new SeededAsset(asset, spec.key(), states[lot], false));
        }
        if ("after".equals(spec.key())) {
            String parent = at(campaign.startDate().plus(fraction(elapsed, 0.90)), () -> assets
                    .registerPhysicalAssetFromDonation(commandId(), organization, "anon:" + UUID.randomUUID(),
                            "BLANKET", BigDecimal.valueOf(30), "UNITS", "bodega-central", "bodega-central",
                            MUNICIPALITY, campaign.campaignRef(), actor)).assetId();
            // La división se pide ahora: el hijo lo crea la saga en otro hilo, con la hora real
            String child = assets.splitPhysicalAsset(commandId(), parent, BigDecimal.valueOf(12), actor);
            awaitChild(parent, child, actor);
            out.add(new SeededAsset(parent, spec.key(), "REGISTERED (dividido)", false));
            out.add(new SeededAsset(child, spec.key(), "REGISTERED (hijo de la división)", true));
        }
        return out;
    }

    /** La huella de una foto de ejemplo de un paso, subida con el mismo servicio que la subida real (ADR-051 F2, F4). */
    private String stepPhoto(MediaPurpose purpose, String organization, String administrator, int seed) {
        return "sha256:" + uploads.store(purpose, organization, null, administrator, photo(seed)).sha256();
    }

    private void awaitChild(String parent, String child, HumanActor actor) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (assetReads.findSplitStatus(parent, child, actor).filter(s -> s == SplitResolutionStatus.CHILD_CREATED)
                    .isPresent()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DemoHistoricSeedFailedException("Interrumpida esperando al hijo de la división");
            }
        }
        throw new DemoHistoricSeedFailedException("El hijo de la división " + child + " no apareció en 60 s");
    }

    /**
     * Una imagen PNG sintética (un rectángulo de color): nunca una foto real ni de personas (ADR-051 F7). Una distinta
     * por lote: una foto de entrega solo puede estar ligada a un activo.
     */
    static byte[] photo(int index) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 48,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setColor(new java.awt.Color(60 + (index * 41) % 180, 150, 90 + (index * 29) % 150));
            g.fillRect(0, 0, 64, 48);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(6 + index % 36, 10, 18, 14);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Duration fraction(Duration total, double fraction) {
        return Duration.ofSeconds(Math.round(total.toSeconds() * fraction));
    }

    private static void require(boolean condition, Spec spec, String what) {
        if (!condition) {
            throw new DemoHistoricSeedFailedException("Semilla histórica, «" + spec.title() + "»: " + what);
        }
    }

    private static String commandId() {
        return UUID.randomUUID().toString();
    }

    /** El único punto de la aplicación que fecha hechos en el pasado. */
    private static <T> T at(Instant occurredAt, Supplier<T> action) {
        return EventTime.callAt(occurredAt, action);
    }
}
