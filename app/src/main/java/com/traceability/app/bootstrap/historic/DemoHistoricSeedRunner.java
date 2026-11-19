package com.traceability.app.bootstrap.historic;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lanza la semilla histórica una sola vez, cuando la aplicación ya acepta tráfico. Ese aviso llega <b>después</b> de
 * {@code ApplicationReadyEvent}, que es cuando corre la semilla de {@code demo-seed}: así la histórica siempre encuentra
 * la organización y las cuentas de la demo sin tocar ese lanzador. Si falla, la excepción detiene el arranque.
 */
@Component
@Profile(DemoHistoricConfig.PROFILE)
public class DemoHistoricSeedRunner {

    private final DemoHistoricSeeder seeder;
    private final AtomicBoolean done = new AtomicBoolean(false);
    private volatile DemoHistoricSeeder.Result result;

    public DemoHistoricSeedRunner(DemoHistoricSeeder seeder) {
        this.seeder = seeder;
    }

    @EventListener
    public void onReady(AvailabilityChangeEvent<ReadinessState> event) {
        if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC && done.compareAndSet(false, true)) {
            result = seeder.seed();
        }
    }

    /** Lo que dejó la semilla al arrancar; {@code null} si todavía no corrió. */
    public DemoHistoricSeeder.Result result() {
        return result;
    }
}
