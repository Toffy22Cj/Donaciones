package com.traceability.app.bootstrap.historic;

import com.traceability.core.domain.shared.EventTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Clock;

/**
 * Perfil {@code demo-historico} (plan del paso 2, 2026-11-16): datos de muestra con fechas pasadas para ver la
 * predicción y los gráficos. Es el <b>único</b> sitio que arma {@link EventTime}; sin este perfil no existen ni el
 * armado, ni el reloj, ni la semilla. Fuera de {@code dev} impide arrancar.
 */
@Configuration
@Profile(DemoHistoricConfig.PROFILE)
public class DemoHistoricConfig {

    public static final String PROFILE = "demo-historico";

    public DemoHistoricConfig(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new DemoHistoricProfileNotAllowedException("El perfil " + PROFILE + " solo se admite junto al perfil"
                    + " dev: fecha hechos en el pasado y es solo para una base local de demostración");
        }
    }

    /** El armado dura lo que el contexto: al cerrarlo, {@link EventTime} vuelve a estar desarmado. */
    @Bean(destroyMethod = "close")
    EventTime.Armed demoHistoricEventTime() {
        return EventTime.arm();
    }

    /** Los servicios lo reciben por {@code ObjectProvider<Clock>}; fuera de un bloque fechado es la hora real. */
    @Bean
    Clock demoHistoricClock() {
        return new EventTimeClock();
    }
}
