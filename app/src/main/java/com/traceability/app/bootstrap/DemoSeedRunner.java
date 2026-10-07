package com.traceability.app.bootstrap;

import identity.application.port.out.AccountRepositoryPort;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.domain.exception.PlatformAlreadyBootstrappedException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Semilla de la demo local (`runbook-demo-local.md`; R9 / Q-v2-1: la organización inicial es semilla, no hay ruta para
 * crearla). <b>Solo con el perfil {@code dev}</b> y {@code traceability.demo.seed.enabled=true}. Crea, si no existen:
 * la cuenta de plataforma (y su autoridad), una organización {@code PENDING_VERIFICATION} con su representante, un
 * administrador y un empleado, y una cuenta de donante. La verificación de la organización la hace el recorrido por
 * HTTP (criterio 1). Idempotente: en un segundo arranque no crea nada.
 * <p>
 * La contraseña común llega por {@code TRACEABILITY_DEMO_SEED_PASSWORD} (sin valor por defecto, al menos 12
 * caracteres); nunca se registra. Los emails usan el dominio reservado {@code .local}.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "traceability.demo.seed.enabled", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {

    public static final String PLATFORM = "plataforma@demo.paxfide.local";
    public static final String REPRESENTATIVE = "representante@demo.paxfide.local";
    public static final String ADMINISTRATOR = "administrador@demo.paxfide.local";
    public static final String EMPLOYEE = "empleado@demo.paxfide.local";
    public static final String DONOR = "donante@demo.paxfide.local";
    public static final String ORGANIZATION_NAME = "Fundación Demo PaxFide";

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    private static final AuditActor SEED = new AuditActor.SystemAuditActor("demo-seed");

    private final CreateAccountService accounts;
    private final AccountRepositoryPort accountRepository;
    private final BootstrapPlatformAuthorityService bootstrap;
    private final CreateOrganizationService organizations;
    private final AddEmployeeService employees;
    private final AssignAdministratorService administrators;
    private final String password;

    public DemoSeedRunner(CreateAccountService accounts, AccountRepositoryPort accountRepository,
                          BootstrapPlatformAuthorityService bootstrap, CreateOrganizationService organizations,
                          AddEmployeeService employees, AssignAdministratorService administrators,
                          @Value("${traceability.demo.seed.password:}") String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("traceability.demo.seed.password (TRACEABILITY_DEMO_SEED_PASSWORD) "
                    + "must be set when traceability.demo.seed.enabled=true");
        }
        this.accounts = accounts;
        this.accountRepository = accountRepository;
        this.bootstrap = bootstrap;
        this.organizations = organizations;
        this.employees = employees;
        this.administrators = administrators;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        AccountId platform = ensureAccount(PLATFORM).getAccountId();
        try {
            bootstrap.bootstrap(PLATFORM);
        } catch (PlatformAlreadyBootstrappedException e) {
            log.info("Demo seed: platform authority already bootstrapped");
        }
        Account representative = ensureAccount(REPRESENTATIVE);
        if (representative.getOrganizationId() == null) {
            Organization organization = organizations.createOrganization(SEED, OrganizationType.FOUNDATION,
                    representative.getAccountId(), ORGANIZATION_NAME);
            AccountId administrator = ensureAccount(ADMINISTRATOR).getAccountId();
            employees.addEmployee(SEED, organization.getOrganizationId(), administrator);
            administrators.assignAdministrator(SEED, organization.getOrganizationId(), administrator);
            employees.addEmployee(SEED, organization.getOrganizationId(), ensureAccount(EMPLOYEE).getAccountId());
            log.info("Demo seed: organization {} created (PENDING_VERIFICATION)", organization.getOrganizationId().value());
        } else {
            log.info("Demo seed: organization already present; nothing created");
        }
        ensureAccount(DONOR);
        log.info("Demo seed: platform account {}", platform.value());
    }

    private Account ensureAccount(String email) {
        return accountRepository.findByEmail(new Email(email))
                .orElseGet(() -> accounts.createAccount(new Email(email), password));
    }
}
