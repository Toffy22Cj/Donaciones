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
 * armado, ni el reloj, ni la semilla. Fuera de {@code dev} impide arrancar, salvo con el permiso explícito
 * {@link #ALLOW_OUTSIDE_DEV} (servidor de pruebas).
 */
@Configuration
@Profile(DemoHistoricConfig.PROFILE)
public class DemoHistoricConfig {

    public static final String PROFILE = "demo-historico";

    /**
     * Permiso explícito para un servidor de pruebas sin {@code dev} (decisión de Manuel, 2026-11-19, fase de
     * pruebas): solo con {@code TRACEABILITY_DEMO_HISTORIC_ALLOW_OUTSIDE_DEV=true}. Nunca en producción.
     */
    public static final String ALLOW_OUTSIDE_DEV = "traceability.demo.historic.allow-outside-dev";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DemoHistoricConfig.class);

    public DemoHistoricConfig(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            return;
        }
        if (environment.getProperty(ALLOW_OUTSIDE_DEV, Boolean.class, false)) {
            log.warn("demo-historico.outside-dev: perfil {} activo SIN dev por {}=true. Fecha hechos en el pasado: "
                    + "solo para un servidor de pruebas, nunca en producción", PROFILE, ALLOW_OUTSIDE_DEV);
            return;
        }
        throw new DemoHistoricProfileNotAllowedException("El perfil " + PROFILE + " solo se admite junto al perfil"
                + " dev: fecha hechos en el pasado y es solo para una base de demostración. En un servidor de pruebas,"
                + " TRACEABILITY_DEMO_HISTORIC_ALLOW_OUTSIDE_DEV=true lo permite sin dev");
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
