package com.traceability.core.support;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sonda de transacción para los tests que afirman atomicidad, rollback o reclamos.
 *
 * <p>Registra, en cada {@code EventStorePort.append} y {@code ProcessedCommandRepositoryPort.tryClaim}, si había una
 * transacción real activa ({@link TransactionSynchronizationManager#isActualTransactionActive()}). Sin un
 * {@code MongoTransactionManager} en el contexto, {@code @Transactional} no tiene efecto y esos tests pasarían sin
 * probar nada; con la sonda, {@link #assertEveryWriteWasTransactional()} los hace fallar.
 *
 * <p>Uso: {@code @Import(TransactionProbe.Config.class)}, {@link #reset()} al empezar la parte transaccional y
 * {@link #assertEveryWriteWasTransactional()} al final.
 */
public class TransactionProbe {

    private static final Set<String> WRITES = Set.of("append", "tryClaim");

    public record Write(String method, boolean transactional) {}

    private final List<Write> writes = new CopyOnWriteArrayList<>();

    public void reset() {
        writes.clear();
    }

    public List<Write> writes() {
        return List.copyOf(writes);
    }

    /** Falla si no hubo ninguna escritura o si alguna se hizo sin transacción activa. */
    public void assertEveryWriteWasTransactional() {
        assertThat(writes).as("la sonda no vio ninguna escritura: el test no ejerce la transacción").isNotEmpty();
        assertThat(writes).as("escrituras sin transacción activa (¿falta MongoTransactionManager en el contexto?)")
                .allMatch(Write::transactional);
    }

    void record(String method) {
        writes.add(new Write(method, TransactionSynchronizationManager.isActualTransactionActive()));
    }

    @TestConfiguration
    public static class Config {

        @Bean
        public TransactionProbe transactionProbe() {
            return new TransactionProbe();
        }

        @Bean
        public static BeanPostProcessor transactionProbeDecorator(org.springframework.beans.factory.ObjectProvider<TransactionProbe> probe) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof EventStorePort) && !(bean instanceof ProcessedCommandRepositoryPort)) {
                        return bean;
                    }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        if (WRITES.contains(invocation.getMethod().getName())) {
                            probe.getObject().record(invocation.getMethod().getName());
                        }
                        return invocation.proceed();
                    });
                    return factory.getProxy();
                }
            };
        }
    }
}
