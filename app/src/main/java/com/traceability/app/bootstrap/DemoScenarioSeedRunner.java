package com.traceability.app.bootstrap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Lanza la semilla de la demo (encargo 6, P5) cuando la aplicación ya escucha peticiones, porque la semilla usa la
 * propia API HTTP. Si falla (por ejemplo, la base no está vacía), la excepción detiene el arranque con su mensaje.
 */
@Component
@Profile("demo-seed")
public class DemoScenarioSeedRunner {

    private final DemoScenarioSeeder seeder;
    private final Environment environment;

    public DemoScenarioSeedRunner(DemoScenarioSeeder seeder, Environment environment) {
        this.seeder = seeder;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        seeder.seed(Integer.parseInt(environment.getRequiredProperty("local.server.port")));
    }
}
