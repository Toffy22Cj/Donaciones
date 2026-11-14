package com.traceability.app.config;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * ADR-054 D3 (cierra el pendiente «origen del {@code correlationId} de scheduler»): cada ejecución de un
 * {@code @Scheduled} lleva su propio {@code correlationId} ({@code sched-<uuid>}) en el MDC, así que los logs de una
 * misma vuelta del anclaje, del coordinador de sagas o de los reintentos de proyección se pueden seguir juntos. Un
 * scheduler que ponga su propio valor (por ejemplo, el de recuperación de fondos por intención) lo sustituye durante
 * esa parte, y el decorador lo limpia al terminar la vuelta.
 */
@Configuration
public class SchedulerCorrelationConfig {

    static final String MDC_KEY = "correlationId";

    static TaskDecorator correlationDecorator() {
        return runnable -> () -> {
            MDC.put(MDC_KEY, "sched-" + UUID.randomUUID());
            try {
                runnable.run();
            } finally {
                MDC.remove(MDC_KEY);
            }
        };
    }

    @Bean
    public ThreadPoolTaskSchedulerCustomizer schedulerCorrelationCustomizer() {
        return scheduler -> scheduler.setTaskDecorator(correlationDecorator());
    }
}
