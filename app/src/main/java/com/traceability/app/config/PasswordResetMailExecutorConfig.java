package com.traceability.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Ejecutor del correo de recuperación (ADR-050 Q5 (a); plan R-4, P-R4-5): propio y con nombre, sin sustituir al de
 * Spring. De 1 a 2 hilos, cola de 100, rechazo con {@code AbortPolicy} (el adaptador lo registra y la petición sigue
 * respondiendo 202) y hasta 10 s de espera al apagar. Sin reintentos: si el correo no llega, se pide otra vez pasado
 * el enfriamiento.
 */
@Configuration
public class PasswordResetMailExecutorConfig {

    public static final String EXECUTOR = "passwordResetMailExecutor";

    /**
     * {@code defaultCandidate = false}: solo lo recibe quien lo pide por nombre ({@code @Qualifier(EXECUTOR)}). Sin ello,
     * al ser un {@code Executor}, Spring Boot 3.4 dejaría de crear su {@code applicationTaskExecutor}
     * ({@code @ConditionalOnMissingBean(Executor.class)}), y el plan R-4 pide que no lo sustituya.
     */
    @Bean(name = EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor passwordResetMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("password-reset-mail-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
